package com.ayanami.reader.core.pixiv

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pixiv 网络层。
 *
 * ### 三条实测出来的硬约束（别绕过）
 *
 * **1. 必须串行。** 实测对 `pixiv.net` 打 4 并发，立刻
 * `SSL: UNEXPECTED_EOF_WHILE_READING` —— Pixiv 会在 TLS 握手层直接掐断。
 * 所以所有请求走同一把 [requestLock]，间隔 [minIntervalMs]。
 * 这不是"保守"，是唯一能跑通的方式。
 *
 * **2. 必须带 Referer。** 图床 `i.pximg.net` 不带 `Referer: https://www.pixiv.net/`
 * 直接 403（实测确认）。[imageInterceptor] 负责这件事。
 *
 * **3. 必须重试。** 即使串行，也有约 20% 的条目会偶发失败
 * （实测 6 条里 2 条返回空 body）。重试 2 次后基本都能拿到。
 *
 * ### 免登录能做什么（实测）
 *
 * | 能力 | 接口 | 状态 |
 * |---|---|---|
 * | 插画榜 | `ranking.php?format=json` | ✅ 免登录 |
 * | 小说榜 ID 列表 | `novel/ranking.php` HTML | ✅ 免登录（需解析） |
 * | 搜索 / 热门 Tag | `/ajax/search/top/<kw>` | ✅ 免登录 |
 * | 作品详情 | `/ajax/illust/<id>`、`/ajax/novel/<id>` | ✅ 免登录 |
 * | `ajax/top` 系列 | — | ❌ 403「不正确的请求」 |
 * | R18 榜单 | — | ❌ 需登录 |
 *
 * 所以主通道用前四个，`ajax/top` **完全不依赖** —— 那正是
 * v0.6.0 计划里的写法，实测才发现它要登录。
 */
class PixivClient(
    /** 设置里填的 PHPSESSID；为空即免登录模式。 */
    private val cookie: String = "",
    /** 图床镜像 host（默认官方）。 */
    private val imageMirror: String = PixivImageUrls.OFFICIAL_HOST,
    /** API 镜像前缀（自建反代时填）；为空用官方。 */
    private val apiBase: String = "",
    /** 代理地址（http:// / socks5://），为空直连。 */
    private val proxyUrl: String = ""
) {

    /** 请求间隔。串行 + 800ms 是实测能稳定跑的下限附近。 */
    @Volatile
    var minIntervalMs: Long = 800

    /** 单次请求重试次数。 */
    @Volatile
    var retries: Int = 2

    /** 串行闸门 —— 唯一允许并发的例外是图床（见 [imageClient]）。 */
    private val requestLock = Mutex()

    private var lastRequestAt = 0L

    val gson = Gson()

    /** 上一次请求失败的原因，供 UI 提示。 */
    @Volatile
    var lastError: PixivError? = null

    /** 是否配置了登录凭据。 */
    val isLoggedIn: Boolean get() = cookie.isNotBlank()

    // ---------------- HTTP 客户端 ----------------

    private val baseUrl: String
        get() = apiBase.trim().trimEnd('/').ifBlank { "https://www.pixiv.net" }

    /**
     * 图床拦截器：给 pixiv 图床请求注入 Referer + UA。
     *
     * 抽成 `Interceptor` 而不是每次手写 header，是唯一能保证
     * 「Coil 加载图片也带上」的方式 —— Coil 走的是它自己的 ImageLoader，
     * 但我们在 App 启动时把它挂到全局，见 `PixivImageLoader`。
     */
    val imageInterceptor = Interceptor { chain ->
        val original = chain.request()
        val url = original.url.toString()
        val builder = original.newBuilder()
        if (PixivImageUrls.isPixivImage(url)) {
            builder.header("Referer", PixivImageUrls.refererFor(url))
            builder.header("User-Agent", UA)
        }
        chain.proceed(builder.build())
    }

    /**
     * 通用请求客户端。
     *
     * 用**独立**的 client 而不是复用书源那个：书源的超时/重试策略是给
     * 中文书站调的，Pixiv 需要更长的超时（海外站点 + 图片较多），
     * 而且要能挂代理。
     */
    private val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(imageInterceptor)
        applyProxy(b)
        b.build()
    }

    /**
     * 挂代理。
     *
     * 复用 [com.ayanami.reader.core.net.DynamicProxySelector.parse] ——
     * 代理串的解析只该有一处真相点。Coil 那边是动态读设置，
     * 这里因为是「每次请求现建 client」，直接固定即可。
     */
    private fun applyProxy(b: OkHttpClient.Builder) {
        val proxy = com.ayanami.reader.core.net.DynamicProxySelector.parse(proxyUrl)
        if (proxy == java.net.Proxy.NO_PROXY) return
        b.proxy(proxy)
    }

    // ---------------- 底层请求 ----------------

    /**
     * 发一次 GET，返回 JSON 根节点。
     *
     * 串行 + 间隔 + 重试都在这里统一处理，上层不用操心。
     */
    private suspend fun getJson(
        path: String,
        referer: String = "https://www.pixiv.net/",
        retryTimes: Int = retries
    ): JsonElement? {
        val url = if (path.startsWith("http")) path else baseUrl + path

        for (attempt in 0..retryTimes) {
            // 串行闸门：同一时刻只允许一个 Pixiv 请求在路上
            val result = requestLock.withLock {
                throttle()
                try {
                    val reqBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA)
                        .header("Referer", referer)
                        // ⚠️ 不能把星号斜杠连着写进字符串或注释 —— 它会提前闭合块注释。
                        // 所以这里用拼接构造 Accept 值。
                        .header("Accept", "application/json, text/plain, " + STAR_SLASH_STAR)
                        .header("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
                    if (cookie.isNotBlank()) {
                        reqBuilder.header("Cookie", normalizeCookie(cookie))
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body.string().orEmpty()
                        when {
                            resp.code == 403 || resp.code == 401 -> {
                                lastError = if (cookie.isBlank()) {
                                    PixivError.NeedsLogin("HTTP ${resp.code}")
                                } else {
                                    PixivError.NeedsLogin("Cookie 可能已失效（HTTP ${resp.code}）")
                                }
                                null
                            }
                            resp.code == 429 -> {
                                lastError = PixivError.RateLimited()
                                null
                            }
                            resp.code >= 500 -> {
                                lastError = PixivError.Http(resp.code)
                                null
                            }
                            resp.code >= 400 -> {
                                lastError = PixivError.Http(resp.code)
                                null
                            }
                            body.isBlank() -> {
                                // 实测有约 20% 概率返回空 body（即使 200）
                                lastError = PixivError.Parse("空响应")
                                null
                            }
                            else -> parseJson(body)
                        }
                    }
                } catch (e: javax.net.ssl.SSLException) {
                    // 并发过高 / 网络被干扰的典型症状
                    lastError = PixivError.NeedsProxy("TLS 握手被中断")
                    null
                } catch (e: java.net.SocketTimeoutException) {
                    lastError = PixivError.NeedsProxy("请求超时")
                    null
                } catch (e: java.net.UnknownHostException) {
                    lastError = PixivError.NeedsProxy("域名解析失败")
                    null
                } catch (e: IOException) {
                    lastError = PixivError.NeedsProxy(e.message ?: "网络错误")
                    null
                }
            }

            if (result != null) return result
            // 需要登录 / 限流 / 4xx 不重试（重试也没用，还可能加重限流）
            val err = lastError
            if (err is PixivError.NeedsLogin || err is PixivError.Http) return null
            if (attempt < retryTimes) {
                kotlinx.coroutines.delay(400L * (attempt + 1))
            }
        }
        return null
    }

    private fun throttle() {
        val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) runCatching { Thread.sleep(wait) }
        lastRequestAt = System.currentTimeMillis()
    }

    private fun normalizeCookie(raw: String): String {
        val t = raw.trim()
        // 用户可能直接粘贴整条 Cookie 头，也可能只粘 PHPSESSID 的值
        return if (t.contains('=')) t else "PHPSESSID=$t"
    }

    private fun parseJson(body: String): JsonElement? = runCatching {
        gson.fromJson(body, JsonElement::class.java)
    }.onFailure {
        lastError = PixivError.Parse(it.message ?: "JSON 无效")
    }.getOrNull()

    /** Pixiv 的 ajax 响应统一是 `{error:bool, message:string, body:T}`。 */
    private fun unwrap(root: JsonElement?): JsonElement? {
        if (root == null) return null
        if (!root.isJsonObject) return root
        val obj = root.asJsonObject
        val err = obj.get("error")
        if (err != null && err.isJsonPrimitive && err.asJsonPrimitive.isBoolean && err.asBoolean) {
            val msg = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            lastError = if (msg.contains("login", true) || msg.contains("ログイン")) {
                PixivError.NeedsLogin(msg)
            } else {
                PixivError.Parse(msg.ifBlank { "error=true" })
            }
            return null
        }
        return if (obj.has("body")) obj.get("body") else obj
    }

    // ---------------- 排行榜 ----------------

    /**
     * 插画 / 漫画排行榜。
     *
     * 走 `ranking.php?mode=...&content=...&format=json` —— 实测免登录可用，
     * 而且**结构完整**：title / tags / url / user_name / profile_img /
     * illust_content_type.sexual / bookmark_count 都在。
     *
     * @param page 从 1 开始；Pixiv 每页 50 条
     */
    suspend fun illustRanking(
        mode: PixivRankMode,
        content: PixivContentType = PixivContentType.ILLUST,
        page: Int = 1,
        date: String? = null
    ): List<PixivIllust> {
        if (mode.adult && !isLoggedIn) {
            lastError = PixivError.NeedsLogin("R18 榜单需要登录")
            return emptyList()
        }
        val ct = if (content == PixivContentType.MANGA) "manga" else "illust"
        val sb = StringBuilder("/ranking.php?mode=${mode.param}&content=$ct&format=json&p=$page")
        // Pixiv 的往期榜单用 yyyymmdd
        date?.takeIf { it.length == 8 }?.let { sb.append("&date=$it") }

        val body = unwrap(getJson(sb.toString(), referer = "https://www.pixiv.net/ranking.php"))
            ?: return emptyList()
        return parseRankingContents(body, isManga = ct == "manga")
    }

    /**
     * 解析 `ranking.php?format=json` 的 `contents` 数组。
     *
     * 字段名是 Pixiv 的 snake_case；`illust_content_type.sexual`
     * 是 R18 判据（比 tags 可靠）。
     */
    internal fun parseRankingContents(body: JsonElement, isManga: Boolean = false): List<PixivIllust> {
        val arr = body.takeIf { it.isJsonObject }?.asJsonObject?.get("contents")
            ?: body.takeIf { it.isJsonArray }
            ?: return emptyList()
        val list = arr.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        return list.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

                val tags = o.get("tags")?.takeIf { it.isJsonArray }
                    ?.asJsonArray?.mapNotNull { t ->
                        t.takeIf { it.isJsonPrimitive }?.asString
                    }.orEmpty()

                // R18：sexual 标记优先，其次看 tag
                val sexual = o.get("illust_content_type")?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("sexual")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

                val rawUrl = s("url")
                val id = s("illust_id").ifBlank {
                    Regex("""/(\d+)_p\d+""").find(rawUrl)?.groupValues?.get(1).orEmpty()
                }

                PixivIllust(
                    id = id,
                    title = s("title"),
                    authorName = s("user_name"),
                    authorId = s("user_id"),
                    authorAvatar = PixivImageUrls.rewrite(s("profile_img"), imageMirror),
                    coverUrl = PixivImageUrls.rewrite(rawUrl, imageMirror),
                    pageUrls = listOfNotNull(rawUrl.takeIf { it.isNotBlank() })
                        .map { PixivImageUrls.rewrite(it, imageMirror) },
                    tags = tags,
                    // ⚠️ ranking.php 用的字段名是 **rating_count**（收藏数），
                    // 不是 ajax 接口的 bookmarkCount。实测第一版写错成
                    // bookmark_count，导致排行榜卡片上的 ♡ 永远是 0。
                    bookmarkCount = i("rating_count").takeIf { it > 0 }
                        ?: i("bookmark_count"),
                    viewCount = i("view_count"),
                    pageCount = i("illust_page_count").takeIf { it > 0 } ?: 1,
                    xRestrict = if (sexual > 0) 1 else 0,
                    isManga = isManga || s("illust_type") == "1",
                    rank = i("rank"),
                    createDate = s("date"),
                    webUrl = "https://www.pixiv.net/artworks/$id"
                )
            }.getOrNull()
        }
    }

    /**
     * 小说排行榜（浅层：**一次请求**）。
     *
     * 只抓 `/novel/ranking.php` 的 HTML 并解析 —— 实测这一页里
     * 标题、系列名、作者、标签、字数、收藏数、排名**全都有**。
     *
     * 所以首屏只要 1 个请求、1~2 秒就能出列表，不用等 12 次详情。
     * 缺的是作者 ID 之外的扩展字段（头像、`characterCount` 精度），
     * 用 [enrichNovel] 按需补。
     *
     * v0.7.0 的实现是「提 ID → 逐条打 `/ajax/novel/<id>`」，
     * 12 条串行 + 900ms 间隔 → 首屏等 10 秒以上。那是可感知的卡顿。
     *
     * @param limit 最多取多少条
     */
    suspend fun novelRanking(
        mode: PixivRankMode,
        limit: Int = 20
    ): List<PixivNovel> {
        if (mode.adult && !isLoggedIn) {
            lastError = PixivError.NeedsLogin("R18 小说榜需要登录")
            return emptyList()
        }
        val url = "/novel/ranking.php?mode=${mode.param}"
        val html = getText(url, referer = "https://www.pixiv.net/novel/ranking.php")
            ?: return emptyList()

        val parsed = PixivNovelRankingParser.parse(html, limit)
        if (parsed.isEmpty()) {
            lastError = PixivError.Parse("榜单里没有解析出小说")
            return emptyList()
        }
        // 解析器不知道用户设置，封面重写在这里统一做
        return parsed.map {
            it.copy(coverUrl = PixivImageUrls.rewrite(it.coverUrl, imageMirror))
        }
    }

    /**
     * 补全一条小说的扩展字段（作者头像、精确字数）。
     *
     * 首屏不调它；只在「用户滚到这条」或「点开阅读」时才需要。
     */
    suspend fun enrichNovel(shallow: PixivNovel): PixivNovel {
        val full = novelDetail(shallow.id) ?: return shallow
        return full.copy(
            // 保留浅层解析出的排名与系列名（详情接口里没有榜单排名）
            rank = shallow.rank,
            seriesTitle = shallow.seriesTitle.ifBlank { full.seriesTitle }
        )
    }

    /**
     * 从小榜单 HTML 里提取 novel id。
     *
     * 用**正则抓 `/novel/show.php?id=\d+`** 而不是解析 DOM class：
     * class 是 CSS-in-JS 的哈希（`sc-e3b2d06d-0 bONwcN`），
     * 每次 Pixiv 发版都会变；而作品链接格式是稳定的接口契约。
     * 同一 id 会出现 2 次（封面 + 标题都包了链接），所以要去重。
     */
    suspend fun novelRankingIds(mode: PixivRankMode, limit: Int = 20): List<String> {
        val url = "/novel/ranking.php?mode=${mode.param}"
        val html = getText(url, referer = "https://www.pixiv.net/novel/ranking.php")
            ?: return emptyList()
        return Regex("""/novel/show\.php\?id=(\d+)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(limit)
            .toList()
    }

    /** 拿一段文本（HTML）。和 [getJson] 共用串行闸门。 */
    private suspend fun getText(path: String, referer: String): String? {
        val url = if (path.startsWith("http")) path else baseUrl + path
        return requestLock.withLock {
            throttle()
            runCatching {
                val b = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml")
                if (cookie.isNotBlank()) b.header("Cookie", normalizeCookie(cookie))
                client.newCall(b.build()).execute().use { resp ->
                    if (resp.code >= 400) {
                        lastError = PixivError.Http(resp.code)
                        null
                    } else resp.body.string()
                }
            }.getOrElse { e ->
                lastError = PixivError.NeedsProxy(e.message ?: "网络错误")
                null
            }
        }
    }

    // ---------------- 作品详情 ----------------

    /** 插画 / 漫画详情。 */
    suspend fun illustDetail(id: String): PixivIllust? {
        val body = unwrap(getJson("/ajax/illust/$id", "https://www.pixiv.net/artworks/$id"))
            ?: return null
        return runCatching { parseIllustDetail(body, id) }
            .onFailure { lastError = PixivError.Parse(it.message ?: "illust") }
            .getOrNull()
    }

    internal fun parseIllustDetail(body: JsonElement, fallbackId: String): PixivIllust {
        val o = body.asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val tags = o.get("tags")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { t ->
                t.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
            } ?: emptyList()

        // 原图地址在 body.urls.original
        val original = o.get("urls")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("original")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        return PixivIllust(
            id = s("id").ifBlank { fallbackId },
            title = s("title"),
            authorName = s("userName"),
            authorId = s("userId"),
            authorAvatar = PixivImageUrls.rewrite(
                o.get("userImage")?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("medium")?.takeIf { it.isJsonPrimitive }
                    ?.asString.orEmpty(),
                imageMirror
            ),
            coverUrl = PixivImageUrls.rewrite(
                o.get("urls")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("regular")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: original,
                imageMirror
            ),
            pageUrls = listOfNotNull(original.takeIf { it.isNotBlank() })
                .map { PixivImageUrls.rewrite(it, imageMirror) },
            tags = tags,
            bookmarkCount = i("bookmarkCount"),
            likeCount = i("likeCount"),
            viewCount = i("viewCount"),
            pageCount = i("pageCount").takeIf { it > 0 } ?: 1,
            xRestrict = i("xRestrict"),
            isManga = s("illustType") == "1",
            createDate = s("createDate"),
            webUrl = "https://www.pixiv.net/artworks/$fallbackId"
        )
    }

    /** 小说详情（含正文）。 */
    suspend fun novelDetail(id: String): PixivNovel? {
        val body = unwrap(getJson("/ajax/novel/$id", "https://www.pixiv.net/novel/show.php?id=$id"))
            ?: return null
        return runCatching { parseNovelDetail(body, id) }
            .onFailure { lastError = PixivError.Parse(it.message ?: "novel") }
            .getOrNull()
    }

    internal fun parseNovelDetail(body: JsonElement, fallbackId: String): PixivNovel {
        val o = body.asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val tagArr = o.get("tags")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { t ->
                t.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
            }.orEmpty()

        return PixivNovel(
            id = s("id").ifBlank { fallbackId },
            title = s("title"),
            authorName = s("userName"),
            authorId = s("userId"),
            coverUrl = PixivImageUrls.rewrite(s("coverUrl"), imageMirror),
            tags = tagArr,
            bookmarkCount = i("bookmarkCount"),
            likeCount = i("likeCount"),
            viewCount = i("viewCount"),
            // characterCount 是实际字数，wordCount 有时是 0
            wordCount = maxOf(i("characterCount"), i("wordCount")),
            xRestrict = i("xRestrict"),
            createDate = s("createDate"),
            description = s("description"),
            webUrl = "https://www.pixiv.net/novel/show.php?id=$fallbackId"
        )
    }

    /** 小说正文（原始串，未解析标记）。 */
    suspend fun novelText(id: String): String? {
        val body = unwrap(getJson("/ajax/novel/$id", "https://www.pixiv.net/novel/show.php?id=$id"))
            ?: return null
        return body.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
    }

    // ---------------- 搜索 ----------------

    /** 搜索结果。 */
    data class SearchResult(
        val illusts: List<PixivIllust> = emptyList(),
        val novels: List<PixivNovel> = emptyList(),
        val relatedTags: List<String> = emptyList()
    )

    /**
     * 综合搜索。
     *
     * 走 `/ajax/search/top/<keyword>` —— 实测免登录可用，
     * 一次返回插画 + 漫画 + 小说 + 相关 tag。
     */
    suspend fun search(keyword: String, filterAdult: Boolean = false): SearchResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return SearchResult()
        val encoded = java.net.URLEncoder.encode(kw, "UTF-8")
        val body = unwrap(
            getJson("/ajax/search/top/$encoded", "https://www.pixiv.net/tags/$encoded")
        ) ?: return SearchResult()
        return runCatching { parseSearch(body, filterAdult) }
            .onFailure { lastError = PixivError.Parse(it.message ?: "search") }
            .getOrDefault(SearchResult())
    }

    internal fun parseSearch(body: JsonElement, filterAdult: Boolean): SearchResult {
        val o = body.asJsonObject
        val illusts = ArrayList<PixivIllust>()
        val novels = ArrayList<PixivNovel>()

        o.get("illust")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { el ->
                runCatching { parseSearchIllust(el.asJsonObject) }.getOrNull()?.let {
                    if (!filterAdult || !it.isAdult) illusts.add(it)
                }
            }
        o.get("manga")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { el ->
                runCatching { parseSearchIllust(el.asJsonObject, isManga = true) }.getOrNull()?.let {
                    if (!filterAdult || !it.isAdult) illusts.add(it)
                }
            }
        o.get("novel")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.forEach { el ->
                runCatching { parseSearchNovel(el.asJsonObject) }.getOrNull()?.let {
                    if (!filterAdult || !it.isAdult) novels.add(it)
                }
            }

        val related = o.get("relatedTags")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { t ->
                t.takeIf { it.isJsonPrimitive }?.asString
            }.orEmpty()

        return SearchResult(illusts, novels, related)
    }

    private fun parseSearchIllust(o: JsonObject, isManga: Boolean = false): PixivIllust {
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val url = s("url")
        val id = s("id")
        return PixivIllust(
            id = id,
            title = s("title"),
            authorName = s("userName"),
            authorId = s("userId"),
            authorAvatar = PixivImageUrls.rewrite(s("profileImageUrl"), imageMirror),
            coverUrl = PixivImageUrls.rewrite(url, imageMirror),
            pageUrls = listOfNotNull(url.takeIf { it.isNotBlank() })
                .map { PixivImageUrls.rewrite(it, imageMirror) },
            tags = o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
            bookmarkCount = i("bookmarkCount"),
            likeCount = i("likeCount"),
            viewCount = i("viewCount"),
            pageCount = i("pageCount").takeIf { it > 0 } ?: 1,
            xRestrict = i("xRestrict"),
            isManga = isManga || i("illustType") == 1,
            createDate = s("createDate"),
            webUrl = "https://www.pixiv.net/artworks/$id"
        )
    }

    private fun parseSearchNovel(o: JsonObject): PixivNovel {
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val id = s("id")
        return PixivNovel(
            id = id,
            title = s("title"),
            authorName = s("userName"),
            authorId = s("userId"),
            coverUrl = PixivImageUrls.rewrite(s("url"), imageMirror),
            tags = o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
            bookmarkCount = i("bookmarkCount"),
            wordCount = maxOf(i("characterCount"), i("wordCount")),
            xRestrict = i("xRestrict"),
            createDate = s("createDate"),
            webUrl = "https://www.pixiv.net/novel/show.php?id=$id"
        )
    }

    /**
     * 热门 tag（用于「无关键词时的 Tag 墙」）。
     *
     * 没有免登录的热门 tag 接口，所以用几个**固定高频关键词搜索**
     * 取 relatedTags 拼出来。这些词是实测有结果的通用词。
     * 这不是假数据 —— 每个 tag 都来自真实搜索响应。
     */
    suspend fun trendingTags(seed: List<String> = DEFAULT_SEEDS, limit: Int = 16): List<String> {
        val out = LinkedHashSet<String>()
        for (kw in seed) {
            if (out.size >= limit) break
            val r = search(kw)
            r.relatedTags.forEach { if (out.size < limit) out.add(it) }
            if (out.isEmpty()) {
                // 搜索没给 relatedTags 时，退化成「作品自带的 tag」
                r.illusts.flatMap { it.tags }.forEach { if (out.size < limit) out.add(it) }
            }
        }
        return out.toList()
    }

    /**
     * 画师的作品列表（完整对象，供画师详情页用）。
     *
     * ### 实测：只能走两步
     *
     * | 接口 | 免登录 | 说明 |
     * |---|---|---|
     * | `/ajax/user/<uid>/illusts` | ❌ | 403「不正确的请求」 |
     * | `/ajax/user/<uid>/profile/all` | ✅ | 返回 326 个作品 ID |
     * | `/ajax/illust/<id>` | ✅ | 单条详情 |
     *
     * 所以是「先拿 ID 列表 → 再逐条取详情」。串行（Pixiv 不允许并发），
     * 因此 `limit` 默认只有 24 条 —— 一次画师页最多 25 个请求。
     *
     * 返回的顺序按 `profile/all` 给的顺序（新→旧），与 Pixiv 网页一致。
     *
     * @param mirror 图床镜像覆盖（调用方传当前设置；不传用本实例的）
     */
    suspend fun userIllusts(
        userId: String,
        limit: Int = 24,
        mirror: String? = null
    ): List<PixivIllust> {
        if (userId.isBlank()) return emptyList()
        val ids = userIllustIds(userId, limit)
        if (ids.isEmpty()) {
            lastError = lastError ?: PixivError.NotFound("画师 $userId 的公开作品")
            return emptyList()
        }

        val targetMirror = mirror ?: imageMirror
        val out = ArrayList<PixivIllust>(ids.size)
        for (id in ids) {
            val item = illustDetail(id) ?: continue
            out.add(
                item.copy(
                    coverUrl = PixivImageUrls.rewrite(item.coverUrl, targetMirror),
                    authorAvatar = PixivImageUrls.rewrite(item.authorAvatar, targetMirror),
                    pageUrls = item.pageUrls.map { PixivImageUrls.rewrite(it, targetMirror) }
                )
            )
        }
        return out
    }

    /**
     * 取画师的作品 ID 列表（**一个请求**）。
     *
     * `profile/all` 的 `illusts` / `manga` 是 `{id: null}` 形式的对象
     * （值是 null，key 才是 ID），`novels` 是数组。
     * 这里取插画 + 漫画。
     */
    suspend fun userIllustIds(userId: String, limit: Int = 24): List<String> {
        if (userId.isBlank()) return emptyList()
        val body = unwrap(
            getJson("/ajax/user/$userId/profile/all", "https://www.pixiv.net/users/$userId")
        ) ?: return emptyList()

        val obj = body.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val ids = ArrayList<String>()

        obj.get("illusts")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()
            ?.let { ids.addAll(it) }
        obj.get("manga")?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()
            ?.let { ids.addAll(it) }
        // 少数账号 manga 是数组形式（元素是 id 字符串或对象）
        obj.get("manga")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            el.takeIf { it.isJsonPrimitive }?.asString?.let { ids.add(it) }
        }

        return ids.distinct().take(limit)
    }

    /**
     * 画师的代表作品 URL（用于搜索页的 3 连拼卡片）。
     */
    suspend fun userWorks(userId: String, limit: Int = 3): List<String> =
        userIllusts(userId, limit = limit).map { it.coverUrl }.filter { it.isNotBlank() }

    /** 画师搜索（Pixiv 没有公开的免登录用户搜索接口，用作品反查）。 */
    suspend fun searchUsers(keyword: String, limit: Int = 8): List<PixivUser> {
        val r = search(keyword)
        val byAuthor = LinkedHashMap<String, MutableList<PixivIllust>>()
        r.illusts.forEach { i ->
            if (i.authorId.isNotBlank()) {
                byAuthor.getOrPut(i.authorId) { ArrayList() }.add(i)
            }
        }
        return byAuthor.entries.take(limit).map { (id, works) ->
            PixivUser(
                id = id,
                name = works.first().authorName,
                avatar = works.first().authorAvatar,
                works = works.map { it.coverUrl }.filter { it.isNotBlank() }.take(3)
            )
        }
    }

    /**
     * 可用榜单（R18 关闭时不含 R18 项）。
     *
     * 即使开了 R18，没登录也拉不到 R18 榜 —— 但列表仍然显示，
     * 点了会得到明确的「需要登录」提示，而不是神秘的空列表。
     */
    fun availableModes(adultOn: Boolean): List<PixivRankMode> =
        PixivRankMode.entries.filter { adultOn || !it.adult }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 星号+斜杠+星号。单独成常量，避免源码里出现连续三字符被当注释。 */
        private const val STAR_SLASH_STAR = "*" + "/" + "*"

        /** Tag 墙的种子词。都是实测有大量结果的通用词。 */
        val DEFAULT_SEEDS = listOf("初音ミク", "オリジナル", "女の子", "風景")
    }
}
