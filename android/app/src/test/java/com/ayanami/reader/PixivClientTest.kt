package com.ayanami.reader

import com.ayanami.reader.core.pixiv.PixivClient
import com.ayanami.reader.core.pixiv.PixivContentType
import com.ayanami.reader.core.pixiv.PixivImageUrls
import com.ayanami.reader.core.pixiv.PixivRankMode
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixiv 网络层契约测试。
 *
 * ### 用真实接口数据当夹具
 *
 * 下面的 JSON **全部来自 2026-11-26 实测抓取的真实响应**
 * （脱敏只截短）。这样测试能锁住「字段名到底是什么」——
 * 这正是第一版最容易写错的地方（`rating_count` 被当成 `bookmark_count`，
 * 结果收藏数永远显示 0）。
 *
 * ### 不测网络
 *
 * 网络行为（串行、重试、超时）依赖外部服务，放在联网台账里测。
 * 这里只测**解析**和**枚举语义**，JVM 上秒跑。
 */
class PixivClientTest {

    private fun client(cookie: String = "", mirror: String = PixivImageUrls.OFFICIAL_HOST) =
        PixivClient(cookie = cookie, imageMirror = mirror)

    // ---------------- 插画榜解析（真实 ranking.php?format=json 结构） ----------------

    /** 真实响应片段：注意字段是 snake_case，收藏数叫 rating_count。 */
    private val rankingJson = """
    {"contents":[
      {"title":"🌙","date":"2026年11月24日 01:24",
       "tags":["白髪","HonkaiStarRail","崩壊スターレイル"],
       "url":"https://i.pximg.net/c/480x960/img-master/img/2026/11/24/01/24/03/152421696_p0_master1200.jpg",
       "illust_type":"0","illust_page_count":"1","user_name":"Sheya",
       "profile_img":"https://i.pximg.net/user-profile/img/2026/07/20/10/53/09/29112187_e83ed218e3250edf8a97cdc97bd830d8_50.jpg",
       "illust_content_type":{"sexual":0},"illust_id":152421696,"user_id":11764388,
       "rank":1,"rating_count":563,"view_count":15277},
      {"title":"たまご寿司","date":"2026年11月24日 20:30",
       "tags":["オリジナル","たにゃご寿司"],
       "url":"https://i.pximg.net/c/480x960/img-master/img/2026/11/24/20/30/23/152445222_p0_master1200.jpg",
       "illust_type":"0","illust_page_count":"1","user_name":"チャイ",
       "profile_img":"https://i.pximg.net/user-profile/img/2021/06/19/00/51/46/20896373_48cca187dc60808fb10161881c14199_50.png",
       "illust_content_type":{"sexual":0},"illust_id":152445222,"user_id":1096811,
       "rank":2,"rating_count":1167,"view_count":14607}
    ]}
    """.trimIndent()

    @Test
    fun `ranking parses real pixiv field names`() {
        val list = client().parseRankingContents(JsonParser.parseString(rankingJson))
        assertEquals(2, list.size)

        val first = list[0]
        assertEquals("152421696", first.id)
        assertEquals("🌙", first.title)
        assertEquals("Sheya", first.authorName)
        assertEquals("11764388", first.authorId)
        assertEquals(1, first.rank)
        assertTrue("标签应解析出来", first.tags.contains("HonkaiStarRail"))
        assertTrue("封面应是 pximg 地址", first.coverUrl.contains("pximg.net"))
        assertTrue("作者头像应解析出来", first.authorAvatar.contains("pximg.net"))
        assertFalse("sexual=0 不该判成人向", first.isAdult)
    }

    @Test
    fun `ranking uses rating_count as bookmark count`() {
        val list = client().parseRankingContents(JsonParser.parseString(rankingJson))
        // 这是第一版写错的字段：错成 bookmark_count 就会永远是 0
        assertEquals("收藏数应取 rating_count", 563, list[0].bookmarkCount)
        assertEquals(1167, list[1].bookmarkCount)
        assertEquals(15277, list[0].viewCount)
    }

    @Test
    fun `ranking marks sexual content as adult`() {
        val json = """
        {"contents":[{"title":"x","url":"https://i.pximg.net/a/1_p0.jpg",
          "illust_id":1,"user_name":"u","illust_content_type":{"sexual":1},
          "rating_count":5,"view_count":1,"rank":1,"tags":[]}]}
        """.trimIndent()
        val list = client().parseRankingContents(JsonParser.parseString(json))
        assertTrue("sexual=1 必须判 R18", list[0].isAdult)
    }

    @Test
    fun `ranking page count is respected`() {
        val json = """
        {"contents":[{"title":"多页漫画","url":"https://i.pximg.net/a/2_p0.jpg",
          "illust_id":2,"user_name":"u","illust_page_count":"12",
          "illust_content_type":{"sexual":0},"rating_count":1,"view_count":1,"rank":1,"tags":[]}]}
        """.trimIndent()
        val list = client().parseRankingContents(JsonParser.parseString(json))
        assertEquals(12, list[0].pageCount)
    }

    @Test
    fun `ranking derives id from url when illust_id missing`() {
        val json = """
        {"contents":[{"title":"x","url":"https://i.pximg.net/img-master/img/2026/1/1/00/00/00/99887766_p0_master1200.jpg",
          "user_name":"u","illust_content_type":{"sexual":0},"rating_count":1,"rank":1,"tags":[]}]}
        """.trimIndent()
        val list = client().parseRankingContents(JsonParser.parseString(json))
        assertEquals("应从 URL 里抽出作品 ID", "99887766", list[0].id)
    }

    @Test
    fun `empty ranking yields empty list not crash`() {
        assertTrue(client().parseRankingContents(JsonParser.parseString("""{"contents":[]}""")).isEmpty())
        assertTrue(client().parseRankingContents(JsonParser.parseString("""{}""")).isEmpty())
    }

    // ---------------- 小说详情解析（真实 ajax/novel 结构） ----------------

    /** 真实 `/ajax/novel/<id>` 响应片段。 */
    private val novelJson = """
    {"bookmarkCount":984,"commentCount":54,"createDate":"2026-11-24T12:33:06+00:00",
     "description":"","id":"29720928","title":"第二十二話　中途採用面接と、更地の見積もり",
     "likeCount":12,"userId":"12070158","userName":"ren.s",
     "viewCount":33300,"xRestrict":0,"content":"彼は[[rb:微笑>ほほえ]]んだ。",
     "coverUrl":"https://i.pximg.net/c/600x600/novel-cover-master/img/2026/11/24/ci29720928_x_master1200.jpg",
     "tags":{"authorId":"12070158","isLocked":false,
       "tags":[{"tag":"夢小説"},{"tag":"ヒロアカ夢"},{"tag":"夢アカ"}]},
     "characterCount":5486,"wordCount":0,"pageCount":1}
    """.trimIndent()

    @Test
    fun `novel detail parses real fields`() {
        val n = client().parseNovelDetail(JsonParser.parseString(novelJson), "29720928")
        assertEquals("29720928", n.id)
        assertEquals("第二十二話　中途採用面接と、更地の見積もり", n.title)
        assertEquals("ren.s", n.authorName)
        assertEquals("12070158", n.authorId)
        assertEquals(984, n.bookmarkCount)
        assertEquals(12, n.likeCount)
        assertFalse(n.isAdult)
    }

    @Test
    fun `novel word count prefers characterCount`() {
        val n = client().parseNovelDetail(JsonParser.parseString(novelJson), "29720928")
        // 真实数据里 wordCount 是 0，characterCount 才是字数
        assertEquals("应取 characterCount", 5486, n.wordCount)
    }

    @Test
    fun `novel tags are extracted from nested object`() {
        val n = client().parseNovelDetail(JsonParser.parseString(novelJson), "29720928")
        assertEquals(listOf("夢小説", "ヒロアカ夢", "夢アカ"), n.tags)
    }

    @Test
    fun `novel xRestrict marks adult`() {
        val adult = novelJson.replace("\"xRestrict\":0", "\"xRestrict\":1")
        assertTrue(client().parseNovelDetail(JsonParser.parseString(adult), "1").isAdult)
    }

    // ---------------- 搜索解析（真实 ajax/search/top 结构） ----------------

    private val searchJson = """
    {"illust":{"data":[
      {"id":"152421696","title":"测试插画","url":"https://i.pximg.net/c/600x600/img-master/img/a.jpg",
       "tags":["白髪","オリジナル"],"userId":"11764388","userName":"Sheya",
       "profileImageUrl":"https://i.pximg.net/user-profile/img/x_50.jpg",
       "bookmarkCount":563,"likeCount":10,"viewCount":15277,"pageCount":1,"xRestrict":0,"illustType":0}
    ]},
     "manga":{"data":[
      {"id":"999","title":"测试漫画","url":"https://i.pximg.net/c/600x600/img-master/img/m.jpg",
       "tags":["漫画"],"userId":"1","userName":"漫画家","bookmarkCount":5,
       "pageCount":30,"xRestrict":0,"illustType":1}
    ]},
     "novel":{"data":[
      {"id":"29720928","title":"测试小说","url":"https://i.pximg.net/novel-cover-master/ci29720928.jpg",
       "tags":["夢小説"],"userId":"12070158","userName":"ren.s",
       "bookmarkCount":984,"characterCount":5486,"xRestrict":0}
    ]},
     "relatedTags":["初音ミク","VOCALOID"]}
    """.trimIndent()

    @Test
    fun `search parses illust manga novel and tags`() {
        val r = client().parseSearch(JsonParser.parseString(searchJson), filterAdult = false)
        assertEquals("插画+漫画合并成 illusts", 2, r.illusts.size)
        assertEquals(1, r.novels.size)
        assertEquals(listOf("初音ミク", "VOCALOID"), r.relatedTags)
        assertTrue("漫画应标记 isManga", r.illusts.any { it.isManga })
        assertEquals("测试小说", r.novels[0].title)
    }

    @Test
    fun `search filterAdult drops r18 results`() {
        val withAdult = searchJson.replace(
            "\"xRestrict\":0,\"illustType\":0",
            "\"xRestrict\":1,\"illustType\":0"
        )
        val filtered = client().parseSearch(JsonParser.parseString(withAdult), filterAdult = true)
        val unfiltered = client().parseSearch(JsonParser.parseString(withAdult), filterAdult = false)

        assertEquals("R18 开启过滤后插画应减少", 1, filtered.illusts.size)
        assertEquals("不过滤时应看到 2 条", 2, unfiltered.illusts.size)
    }

    // ---------------- 榜单模式的 R18 可见性 ----------------

    @Test
    fun `r18 modes hidden when adult off`() {
        val modes = client().availableModes(adultOn = false)
        assertTrue("R18 关闭时不该有 R18 项", modes.none { it.adult })
        assertTrue("应该有日/周/月榜", modes.contains(PixivRankMode.DAILY))
    }

    @Test
    fun `r18 modes visible when adult on`() {
        val modes = client().availableModes(adultOn = true)
        assertTrue("R18 开启后应出现 R18 项", modes.any { it.adult })
        assertTrue(modes.contains(PixivRankMode.R18_DAILY))
        assertTrue(modes.contains(PixivRankMode.R18_WEEKLY))
    }

    @Test
    fun `r18 ranking refuses without login`() {
        val c = client(cookie = "")
        // 没登录时 R18 榜必须明确返回空并给出 NeedsLogin，不能安静返回空列表
        val r = kotlinx.coroutines.runBlocking { c.illustRanking(PixivRankMode.R18_DAILY) }
        assertTrue(r.isEmpty())
        assertTrue(
            "应明确告知需要登录，实际=${c.lastError}",
            c.lastError is com.ayanami.reader.core.pixiv.PixivError.NeedsLogin
        )
    }

    @Test
    fun `logged in flag follows cookie presence`() {
        assertFalse(client(cookie = "").isLoggedIn)
        assertTrue(client(cookie = "12345678_abcdef").isLoggedIn)
    }

    // ---------------- 内容类型参数 ----------------

    @Test
    fun `content type params match pixiv api`() {
        assertEquals("illust", PixivContentType.ILLUST.param)
        assertEquals("manga", PixivContentType.MANGA.param)
        assertEquals("novel", PixivContentType.NOVEL.param)
    }

    @Test
    fun `rank mode params match pixiv api`() {
        assertEquals("daily", PixivRankMode.DAILY.param)
        assertEquals("weekly", PixivRankMode.WEEKLY.param)
        assertEquals("monthly", PixivRankMode.MONTHLY.param)
        // 实测：R18 榜的参数是 daily_r18 / weekly_r18
        assertEquals("daily_r18", PixivRankMode.R18_DAILY.param)
        assertEquals("weekly_r18", PixivRankMode.R18_WEEKLY.param)
    }

    // ---------------- 图片镜像重写（全走真实 URL 形态） ----------------

    private val realThumb =
        "https://i.pximg.net/c/480x960/img-master/img/2026/11/24/01/24/03/152421696_p0_master1200.jpg"

    @Test
    fun `mirror rewrite replaces host only`() {
        val out = PixivImageUrls.rewrite(realThumb, "i.pixiv.re")
        assertEquals(
            "https://i.pixiv.re/c/480x960/img-master/img/2026/11/24/01/24/03/152421696_p0_master1200.jpg",
            out
        )
    }

    @Test
    fun `mirror rewrite keeps path exactly`() {
        val a = PixivImageUrls.rewrite(realThumb, "i.pixiv.re")
        val pathA = a.substringAfter("i.pixiv.re")
        val pathB = realThumb.substringAfter("i.pximg.net")
        assertEquals("path 必须原样保留（含尺寸段与文件名）", pathB, pathA)
    }

    @Test
    fun `official mirror is a no-op`() {
        assertEquals(realThumb, PixivImageUrls.rewrite(realThumb, "i.pximg.net"))
        assertEquals(realThumb, PixivImageUrls.rewrite(realThumb, null))
        assertEquals(realThumb, PixivImageUrls.rewrite(realThumb, ""))
    }

    @Test
    fun `switching back from mirror restores official host`() {
        val mirrored = PixivImageUrls.rewrite(realThumb, "i.pixiv.re")
        val restored = PixivImageUrls.rewrite(mirrored, null)
        assertEquals("切回直连必须还原官方域名", realThumb, restored)
    }

    @Test
    fun `mirror accepts url with scheme`() {
        val out = PixivImageUrls.rewrite(realThumb, "https://i.pixiv.re")
        assertTrue(out.startsWith("https://i.pixiv.re/"))
    }

    @Test
    fun `non pixiv url is untouched`() {
        val other = "https://example.com/a.jpg"
        assertEquals(other, PixivImageUrls.rewrite(other, "i.pixiv.re"))
    }

    @Test
    fun `isPixivImage recognizes official and mirrors`() {
        assertTrue(PixivImageUrls.isPixivImage(realThumb))
        assertTrue(PixivImageUrls.isPixivImage("https://i.pixiv.re/x.jpg"))
        assertTrue(PixivImageUrls.isPixivImage("https://i.pximg.net/user-profile/img/a_50.jpg"))
        assertFalse(PixivImageUrls.isPixivImage("https://example.com/a.jpg"))
        assertFalse(PixivImageUrls.isPixivImage(""))
    }

    @Test
    fun `rewrite applies to author avatar too`() {
        val avatar = "https://i.pximg.net/user-profile/img/2026/07/20/29112187_x_50.jpg"
        val out = PixivImageUrls.rewrite(avatar, "i.pixiv.cat")
        assertTrue(out.startsWith("https://i.pixiv.cat/user-profile/"))
    }

    @Test
    fun `client rewrites parsed urls through mirror setting`() {
        val c = client(mirror = "i.pixiv.re")
        val list = c.parseRankingContents(JsonParser.parseString(rankingJson))
        assertTrue("封面应走镜像", list[0].coverUrl.startsWith("https://i.pixiv.re/"))
        assertTrue("头像也应走镜像", list[0].authorAvatar.startsWith("https://i.pixiv.re/"))
    }

    // ---------------- 错误分类 ----------------

    @Test
    fun `error messages are actionable`() {
        val needLogin = com.ayanami.reader.core.pixiv.PixivError.NeedsLogin("403")
        assertTrue("登录错误要指路", needLogin.humanMessage.contains("设置"))

        val proxy = com.ayanami.reader.core.pixiv.PixivError.NeedsProxy("TLS 被中断")
        assertTrue("网络错误要提到代理/镜像", proxy.humanMessage.contains("代理"))

        val rate = com.ayanami.reader.core.pixiv.PixivError.RateLimited()
        assertTrue("限流要提示稍等", rate.humanMessage.contains("限流"))
    }

    // ---------------- 图片拦截器 ----------------

    @Test
    fun `image interceptor injects referer for pximg`() {
        val c = client()
        val req = okhttp3.Request.Builder()
            .url(realThumb)
            .build()
        val chain = FakeChain(req)
        runCatching { c.imageInterceptor.intercept(chain) }
        val sent = chain.last!!
        assertEquals(
            "图床请求必须带 pixiv 的 Referer（不带会 403）",
            "https://www.pixiv.net/",
            sent.header("Referer")
        )
        assertNotNull("必须带 UA", sent.header("User-Agent"))
    }

    @Test
    fun `image interceptor leaves other hosts alone`() {
        val c = client()
        val req = okhttp3.Request.Builder().url("https://example.com/a.jpg").build()
        val chain = FakeChain(req)
        runCatching { c.imageInterceptor.intercept(chain) }
        assertEquals("非图床请求不该被注入 Referer", null, chain.last!!.header("Referer"))
    }

    /**
     * 最小 Interceptor.Chain 替身，只为检查拦截器改写了什么。
     *
     * 不构造真的 `Response` —— OkHttp 5 的 `Response.Builder.request(…)`
     * 已废弃，用它来测「头有没有加上」既绕又会有编译警告。
     * 拦截器在 `chain.proceed()` 之后就直接 return，所以这里
     * 记下 Request 然后抛一个哨兵异常即可。
     */
    private class FakeChain(private val request: okhttp3.Request) : okhttp3.Interceptor.Chain {
        var last: okhttp3.Request? = null
        override fun request() = request
        override fun proceed(request: okhttp3.Request): okhttp3.Response {
            last = request
            throw StopChain
        }
        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    /** 用于打断 chain 的哨兵异常。 */
    private object StopChain : RuntimeException("stop")

    // ---------------- 小说榜 HTML 解析（原 TagWallDiagnostic 的核心断言） ----------------

    /**
     * 真实 `/novel/ranking.php` 的条目结构（截取自 2026-11-26 实测）。
     *
     * 三个关键点（第一版全踩了）：
     * 1. 标题在 `href` **之后**，不是之前
     * 2. 作者名在 `title` 属性上，标签体内是空 figure
     * 3. 系列名也是 `title` 属性（`/novel/series/` 那个链接）
     */
    private val rankingHtml = """
    <div><a href="/novel/show.php?id=29720928"><div><figure></figure></div></a></div>
    <a title="世紀末に備えすぎた転生OL、雄英の担任が元飲み仲間だった件【ヒロアカ】"
       href="/novel/series/16695172">世紀末に備えすぎた…</a>
    <div title="第二十二話　中途採用面接と、更地の見積もり">
      <a href="/novel/show.php?id=29720928">第二十二話　中途採用面接と…</a>
    </div>
    <a data-gtm-value="12070158" href="/users/12070158">
      <div title="ren.s" role="img"><figure></figure></div>
    </a>
    <a data-gtm-label="夢小説" href="/tags/x">夢小説</a>
    <a data-gtm-label="ヒロアカ夢" href="/tags/y">ヒロアカ夢</a>
    <div>11,163文字</div><div>22分</div><div>982</div>
    <img src="https://i.pximg.net/c/100x100/novel-cover-master/img/2026/11/24/ci29720928_abc_master1200.jpg">
    <div><a href="/novel/show.php?id=29715074"><figure></figure></a></div>
    <div title="秘密を隠してすれ違う。"><a href="/novel/show.php?id=29715074">秘密を隠して…</a></div>
    <a data-gtm-value="1777861" href="/users/1777861"><div title="酉" role="img"></div></a>
    <a data-gtm-label="鬼滅の夢" href="/tags/z">鬼滅の夢</a>
    <div>45,856文字</div><div>90分</div><div>1042</div>
    <img src="https://i.pximg.net/c/100x100/novel-cover-master/img/2026/11/22/ci29715074_def_master1200.jpg">
    """.trimIndent()

    /**
     * 纯 HTML 夹具走**保底路径**：只能拿到 id + 标题。
     *
     * 完整字段（作者/字数/收藏/系列）只有内嵌 `rank_a` 才有 ——
     * 见 `parses real rank_a fixture with all fields`。
     * 这里只锁「保底不崩、id 和标题都对」。
     */
    @Test
    fun `html fallback extracts ids and titles in order`() {
        val list = com.ayanami.reader.core.pixiv.PixivNovelRankingParser.parse(rankingHtml)
        assertEquals("应解析出 2 条", 2, list.size)

        assertEquals("29720928", list[0].id)
        assertEquals("第二十二話　中途採用面接と、更地の見積もり", list[0].title)
        assertEquals(1, list[0].rank)

        assertEquals("29715074", list[1].id)
        assertEquals("秘密を隠してすれ違う。", list[1].title)
        assertEquals(2, list[1].rank)
    }

    @Test
    fun `novel ranking html empty input is safe`() {
        assertTrue(com.ayanami.reader.core.pixiv.PixivNovelRankingParser.parse("").isEmpty())
        assertTrue(com.ayanami.reader.core.pixiv.PixivNovelRankingParser.parse("<html></html>").isEmpty())
    }

    @Test
    fun `word count parsing handles wan and plain`() {
        val p = com.ayanami.reader.core.pixiv.PixivNovelRankingParser
        assertEquals(11163, p.parseWordCount(listOf("11,163文字")))
        assertEquals(12000, p.parseWordCount(listOf("1.2万字")))
        assertEquals(982, p.parseWordCount(listOf("982文字")))
        assertEquals(0, p.parseWordCount(listOf("没有字数")))
    }

    @Test
    fun `bookmark parsing takes number after duration`() {
        val p = com.ayanami.reader.core.pixiv.PixivNovelRankingParser
        assertEquals(982, p.parseBookmark(listOf("11,163文字", "22分", "982")))
        assertEquals(0, p.parseBookmark(listOf("11,163文字")))
    }



    // ---------------- 真实榜单夹具（内嵌 rank_a JSON） ----------------

    /**
     * 用**真实 `rank_a` 数据**（从 2026-11-26 实测榜单里原样切出前 4 条）
     * 验证解析。
     *
     * 这个夹具是照真实页面形态生成的：JSON 直接内嵌在
     * `<script id="__NEXT_DATA__">` 里，**不做额外转义**。
     *
     * 踩过的坑（两版都失败）：
     * 1. 用正则按 `<div title=…><a href=…>` 切块，块长限 8000，
     *    实测间距到 8922 → 作者/字数全丢
     * 2. 误以为内嵌 JSON 是转义的，加了 `\"` → `"` 的还原 →
     *    把合法 JSON 弄坏 → 全部退回「只有 id 标题」的保底路径
     */
    @Test
    fun `parses real rank_a fixture with all fields`() {
        val f = listOf(
            java.io.File("src/test/fixtures/novel_ranking_rank_a.html"),
            java.io.File("app/src/test/fixtures/novel_ranking_rank_a.html"),
            java.io.File("../app/src/test/fixtures/novel_ranking_rank_a.html")
        ).firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("找不到真实夹具，跳过", f != null)

        val raw = f!!.readText()
        val arr = com.ayanami.reader.core.pixiv.PixivNovelRankingParser.embeddedEntries(raw)
        assertEquals("应能从内嵌 JSON 里找到 rank_a", 4, arr?.size() ?: 0)

        val list = com.ayanami.reader.core.pixiv.PixivNovelRankingParser.parse(raw, 4)
        assertEquals(4, list.size)

        val a = list[0]
        assertEquals("29720928", a.id)
        assertEquals("第二十二話　中途採用面接と、更地の見積もり", a.title)
        assertEquals("ren.s", a.authorName)
        assertEquals("12070158", a.authorId)
        assertEquals(11163, a.wordCount)
        assertEquals(971, a.bookmarkCount)
        assertEquals(1, a.rank)
        assertEquals("系列名应从 series_title 取",
            "世紀末に備えすぎた転生OL、雄英の担任が元飲み仲間だった件【ヒロアカ】", a.seriesTitle)
        assertTrue("标签", a.tags.contains("ヒロアカ夢"))
        assertTrue("封面", a.coverUrl.contains("pximg"))

        val b = list[1]
        assertEquals("29715074", b.id)
        assertEquals(45856, b.wordCount)
        assertEquals(1056, b.bookmarkCount)

        // 每条都必须字段齐全 —— 这是「块被截断 / JSON 解析失败」最直接的信号
        assertTrue(
            "每条都要有作者，实测=" + list.map { it.authorName },
            list.all { it.authorName.isNotBlank() }
        )
        assertTrue(
            "每条都要有字数，实测=" + list.map { it.wordCount },
            list.all { it.wordCount > 0 }
        )
        assertTrue(
            "每条都要有封面，实测=" + list.map { it.coverUrl.take(30) },
            list.all { it.coverUrl.isNotBlank() }
        )
    }

    @Test
    fun `rank_a takes precedence over html scaping`() {
        // 同一份 raw 里既有 rank_a 又有可解析的 HTML 时，必须用 rank_a
        // （否则会退化成「只有 id 标题」的低质量数据）
        val f = listOf(
            java.io.File("src/test/fixtures/novel_ranking_rank_a.html"),
            java.io.File("app/src/test/fixtures/novel_ranking_rank_a.html")
        ).firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("找不到真实夹具，跳过", f != null)
        val list = com.ayanami.reader.core.pixiv.PixivNovelRankingParser.parse(f!!.readText(), 4)
        assertTrue("必须用 rank_a 的完整字段，而不是 HTML 保底",
            list.all { it.authorName.isNotBlank() && it.wordCount > 0 })
    }

}
