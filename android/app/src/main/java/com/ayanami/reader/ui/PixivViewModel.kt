package com.ayanami.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ayanami.reader.core.pixiv.PixivClient
import com.ayanami.reader.core.pixiv.PixivContentType
import com.ayanami.reader.core.pixiv.PixivError
import com.ayanami.reader.core.pixiv.PixivIllust
import com.ayanami.reader.core.pixiv.PixivImageUrls
import com.ayanami.reader.core.pixiv.PixivNovel
import com.ayanami.reader.core.pixiv.PixivNovelParser
import com.ayanami.reader.core.pixiv.PixivRankMode
import com.ayanami.reader.core.pixiv.PixivUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pixiv 页的 UI 状态。
 *
 * 用 Compose 的 `mutableStateOf` 而不是 StateFlow：这个页面的数据
 * 全是「一次拉一批、局部替换」，没有跨进程观察的需求，
 * 走快照系统能让列表更新立刻生效，少一层 collect。
 */
class PixivUiState {

    /** 当前选中的子 Tab。 */
    var tab by mutableStateOf(PixivTab.RANKING)

    /** 当前榜单模式。 */
    var rankMode by mutableStateOf(PixivRankMode.DAILY)

    /** 当前内容类型（插画 / 漫画 / 小说）。 */
    var contentType by mutableStateOf(PixivContentType.ILLUST)

    /** 是否开启 R18 视图（跟随全局开关，但可单独收起）。 */
    var adultOn by mutableStateOf(false)

    // ================= 榜单侧（PixivScreen 用）=================
    //
    // ⚠️ v0.7.0 把「榜单」和「搜索」共用 `illusts` / `novels`，
    // 结果在搜索页搜完插画切回 Pixiv，榜单被搜索结果的 48 条覆盖；
    // 反过来在 Pixiv 看榜单再去搜，搜索框下面显示的是榜单内容。
    // 两边数据来源、排序、分页语义完全不同，必须分开。

    /** 榜单插画（横向 / 网格用）。 */
    val rankingIllusts = mutableStateListOf<PixivIllust>()

    /** 榜单小说（首屏来自 HTML 浅解析，之后可增量补全）。 */
    val rankingNovels = mutableStateListOf<PixivNovel>()

    /** 热门标签墙的词表。 */
    val rankingTags = mutableStateListOf<String>()

    /** Tag 墙封面：tag → 首图。 */
    var tagCovers by mutableStateOf<Map<String, String>>(emptyMap())

    /** Tag 墙取封面的进度（当前 / 总数）。 */
    var tagProgress by mutableStateOf<Pair<Int, Int>?>(null)

    /** 榜单加载中。 */
    var rankingLoading by mutableStateOf(false)

    /** 榜单翻页中。 */
    var rankingLoadingMore by mutableStateOf(false)

    var rankingPage by mutableStateOf(1)
    var rankingHasMore by mutableStateOf(true)

    /** 榜单错误提示。**永远不吞掉**。 */
    var rankingError by mutableStateOf<String?>(null)

    /** 榜单首次加载完成过（区分「还没拉」和「拉完是空的」）。 */
    var rankingLoadedOnce by mutableStateOf(false)

    /**
     * 小说榜的增量补全进度（已补 / 总数）。
     *
     * 首屏先用 HTML 浅数据上屏，之后后台逐条补作者头像/精确字数。
     * UI 用它显示「正在补全 3/12」，而不是让用户干等。
     */
    var novelEnrichProgress by mutableStateOf<Pair<Int, Int>?>(null)

    // ================= 搜索侧（SearchScreen 用）=================

    /** 搜索到的插画。 */
    val searchIllusts = mutableStateListOf<PixivIllust>()

    /** 搜索到的小说。 */
    val searchNovels = mutableStateListOf<PixivNovel>()

    /** 搜索到的画师。 */
    val searchUsers = mutableStateListOf<PixivUser>()

    /** 搜索相关标签（用于结果上方的推荐胶囊）。 */
    val searchTags = mutableStateListOf<String>()

    var searchLoading by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searchLoadedOnce by mutableStateOf(false)

    /** 搜索关键词（Pixiv 侧，和书源搜索分开）。 */
    var keyword by mutableStateOf("")

    /** 已被搜索过一次的关键词（空串表示还没搜过，UI 据此显示 Tag 墙）。 */
    var searchedKeyword by mutableStateOf("")

    // ================= 画师详情 =================

    /** 当前打开的画师（null = 未打开）。 */
    var openedArtist by mutableStateOf<PixivUser?>(null)

    /** 该画师的作品列表。 */
    val artistWorks = mutableStateListOf<PixivIllust>()

    var artistWorksLoading by mutableStateOf(false)
    var artistWorksError by mutableStateOf<String?>(null)

    // ================= 全局 =================

    /** 当前打开的大图浏览器位置（null = 关闭）。 */
    var viewerIllusts by mutableStateOf<List<PixivIllust>>(emptyList())
    var viewerIndex by mutableStateOf(0)

    /** 当前正在阅读的 Pixiv 小说。 */
    var readingNovel by mutableStateOf<PixivNovel?>(null)
    var readingText by mutableStateOf<String?>(null)
    var readingLoading by mutableStateOf(false)

    /** 重置榜单（换榜模式时调用，**不碰搜索状态**）。 */
    fun resetRanking() {
        rankingIllusts.clear()
        rankingNovels.clear()
        rankingPage = 1
        rankingHasMore = true
        rankingError = null
        rankingLoadedOnce = false
        novelEnrichProgress = null
    }

    /** 重置搜索结果。 */
    fun resetSearch() {
        searchIllusts.clear()
        searchNovels.clear()
        searchUsers.clear()
        searchTags.clear()
        searchError = null
        searchLoadedOnce = false
        searchedKeyword = ""
    }

    /** 判断某个 tag 的封面是否还在补。 */
    val tagWallBuilding: Boolean get() = tagProgress != null
}

/** Pixiv 页的子 Tab。 */
enum class PixivTab(val label: String) {
    RANKING("排行榜"),
    TAGS("热门标签"),
    NOVEL("小说"),
    USERS("画师")
}

/** 搜索结果模式（搜索页的 Pixiv 子 Tab）。 */
enum class PixivSearchMode(val label: String) {
    ILLUST("插画·漫画"),
    NOVEL("小说"),
    USER("画师")
}

// ---------------- 客户端 ----------------

/**
 * 按当前设置构造 [PixivClient]。
 *
 * 每次用时现建：设置可能在两次请求之间被改（换镜像、填 Cookie），
 * 缓存实例会让用户改完不生效 —— 那是最容易让人放弃的体验。
 */
fun ReaderViewModel.newPixivClient(): PixivClient = PixivClient(
    cookie = settings.pixivCookie,
    imageMirror = settings.pixivImageMirror,
    apiBase = settings.pixivApiBase,
    proxyUrl = settings.proxyUrl
).apply {
    // 保守一点：串行 + 900ms。实测 Pixiv 对并发极其敏感。
    minIntervalMs = 900
    retries = 2
}

/** 把错误翻译成可操作的提示（必须指路到设置）。 */
internal fun PixivError.toUiMessage(): String = when (this) {
    is PixivError.NeedsLogin -> "需要登录 Pixiv。去「设置 → Pixiv」填 PHPSESSID，或先看非 R18 榜单。"
    is PixivError.NeedsProxy -> "连不上 Pixiv（$detail）。去「设置 → Pixiv」配代理，或把图片镜像换成反代域名。"
    is PixivError.RateLimited -> "Pixiv 限流了，歇一会儿再刷。"
    is PixivError.NotFound -> humanMessage
    is PixivError.Parse -> "Pixiv 数据结构变了：$detail。这可能是 App 需要更新。"
    is PixivError.Http -> "Pixiv 返回 HTTP $code。稍后再试，或检查代理设置。"
}

// ---------------- 加载 ----------------

/**
 * 加载榜单首屏（**只动榜单状态，不碰搜索状态**）。
 *
 * ### 小说榜：先上屏，再补全
 *
 * `novelRanking()` 只发 **1 个请求**（抓榜单 HTML），
 * HTML 里已有标题/系列名/作者/标签/字数/收藏/排名 —— 足够渲染卡片。
 *
 * 之后后台**逐条**补 `/ajax/novel/<id>`（作者头像、精确字数）。
 * 补全失败不影响已显示的内容 —— 这点很重要：用户已经能读列表了，
 * 不该因为第 7 条补全失败就把整屏清空。
 *
 * v0.7.0 是「12 条全部串行拉完才渲染」，首屏 10 秒以上。
 */
fun ReaderViewModel.loadPixivRanking(force: Boolean = false) {
    if (pixivState.rankingLoading) return
    if (pixivState.rankingLoadedOnce && !force) return

    pixivState.rankingLoading = true
    pixivState.rankingError = null
    viewModelScope.launch {
        val client = newPixivClient()
        val mode = pixivState.rankMode
        val type = pixivState.contentType

        withContext(Dispatchers.IO) {
            if (type == PixivContentType.NOVEL) {
                // ---- 第一步：1 个请求，立即上屏 ----
                val shallow = client.novelRanking(mode, limit = NOVEL_PAGE_SIZE)
                pixivState.rankingNovels.clear()
                pixivState.rankingNovels.addAll(shallow)
                pixivState.rankingHasMore = shallow.size >= NOVEL_PAGE_SIZE
                client.lastError?.let { pixivState.rankingError = it.toUiMessage() }
            } else {
                val list = client.illustRanking(mode, type, page = 1)
                pixivState.rankingIllusts.clear()
                pixivState.rankingIllusts.addAll(list)
                pixivState.rankingHasMore = list.size >= 40
                client.lastError?.let { pixivState.rankingError = it.toUiMessage() }
            }
        }
        pixivState.rankingPage = 1
        pixivState.rankingLoading = false
        pixivState.rankingLoadedOnce = true

        // ---- 第二步：小说榜后台静默补全 ----
        if (type == PixivContentType.NOVEL && pixivState.rankingNovels.isNotEmpty()) {
            enrichNovelListInBackground()
        }
    }
}

/**
 * 后台逐条补全小说榜的扩展字段。
 *
 * 串行（Pixiv 不允许并发），每补完一条就 `update` 那一行 ——
 * 列表是 `SnapshotStateList`，替换元素会触发对应 item 重组，
 * 不需要整体换列表（那会让滚动位置跳动）。
 */
private fun ReaderViewModel.enrichNovelListInBackground() {
    if (pixivState.novelEnrichProgress != null) return
    viewModelScope.launch {
        val client = newPixivClient()
        val total = pixivState.rankingNovels.size
        pixivState.novelEnrichProgress = 0 to total

        for (i in 0 until total) {
            val shallow = pixivState.rankingNovels.getOrNull(i) ?: break
            val full = withContext(Dispatchers.IO) { client.enrichNovel(shallow) }
            // 用 id 重新定位（补全期间用户可能翻页/换榜）
            val idx = pixivState.rankingNovels.indexOfFirst { it.id == shallow.id }
            if (idx >= 0 && full != shallow) {
                pixivState.rankingNovels[idx] = full
            }
            pixivState.novelEnrichProgress = (i + 1) to total
        }
        pixivState.novelEnrichProgress = null
    }
}

/** 每页小说条数。 */
private const val NOVEL_PAGE_SIZE = 12

/** 翻下一页（只影响榜单状态）。 */
fun ReaderViewModel.loadMorePixiv() {
    if (pixivState.rankingLoading || pixivState.rankingLoadingMore) return
    if (!pixivState.rankingHasMore) return
    if (pixivState.contentType == PixivContentType.NOVEL) return

    pixivState.rankingLoadingMore = true
    viewModelScope.launch {
        val next = pixivState.rankingPage + 1
        val client = newPixivClient()
        val list = withContext(Dispatchers.IO) {
            client.illustRanking(pixivState.rankMode, pixivState.contentType, page = next)
        }
        if (list.isEmpty()) {
            pixivState.rankingHasMore = false
            client.lastError?.let { pixivState.rankingError = it.toUiMessage() }
        } else {
            // 按 id 去重后追加 —— Pixiv 翻页有重叠
            val existing = pixivState.rankingIllusts.mapTo(HashSet()) { it.id }
            pixivState.rankingIllusts.addAll(list.filterNot { it.id in existing })
            pixivState.rankingPage = next
        }
        pixivState.rankingLoadingMore = false
    }
}

/** 切换榜单模式并重载（只重置榜单）。 */
fun ReaderViewModel.setPixivRank(mode: PixivRankMode, type: PixivContentType) {
    if (pixivState.rankMode == mode && pixivState.contentType == type) return
    pixivState.rankMode = mode
    pixivState.contentType = type
    pixivState.resetRanking()
    loadPixivRanking(force = true)
}

/**
 * 加载热门标签墙。
 *
 * ### 为什么必须同时拉封面
 *
 * 第一版只取了 tag 字符串，结果 Tag 墙 16 个格子全是空白灰块 ——
 * UI 那边用「在这个 tag 的作品里找封面」来取图，但作品列表是空的。
 *
 * 现在改成：对**每个 tag 搜一次**，把该 tag 的首图当作封面。
 * 这样格子有真图，而且点进去的结果和封面是同一个 tag，不会驴唇不对马嘴。
 *
 * 代价是 N 次搜索请求。Pixiv 必须串行（实测并发直接 TLS 断开），
 * 所以只取前 [TAG_WALL_COUNT] 个 tag，且带进度回调让 UI 能显示"3/8"。
 */
fun ReaderViewModel.loadPixivTags(force: Boolean = false) {
    if (pixivState.rankingLoading) return
    if (pixivState.rankingTags.isNotEmpty() && !force) return

    pixivState.rankingLoading = true
    pixivState.rankingError = null
    pixivState.tagCovers = emptyMap()

    viewModelScope.launch {
        val client = newPixivClient()
        val adultOn = settings.adultEnabled

        withContext(Dispatchers.IO) {
            val tags = client.trendingTags(limit = TAG_WALL_COUNT)
            pixivState.rankingTags.clear()
            pixivState.rankingTags.addAll(tags)

            // 逐个 tag 取一张封面。串行（Pixiv 不允许并发）
            tags.forEachIndexed { i, tag ->
                pixivState.tagProgress = (i + 1) to tags.size
                val r = client.search(tag, filterAdult = !adultOn)
                val cover = r.illusts.firstOrNull()?.thumb.orEmpty()
                if (cover.isNotBlank()) {
                    pixivState.tagCovers = pixivState.tagCovers + (tag to cover)
                }
            }
            client.lastError?.let { pixivState.rankingError = it.toUiMessage() }
        }
        pixivState.tagProgress = null
        pixivState.rankingLoading = false
        pixivState.rankingLoadedOnce = true
    }
}

/** Tag 墙的格子数。每格要一次搜索，所以别设太大。 */
private const val TAG_WALL_COUNT = 8

// ---------------- 画师详情 ----------------

/**
 * 打开画师的作品列表。
 *
 * 数据来源：搜索结果里已经抓到的同作者作品（`searchIllusts` 里
 * `authorId` 匹配的那些）+ 再向 Pixiv 拉一次该画师的作品。
 *
 * 只用搜索结果不行 —— 那只覆盖本次搜到的那几张；
 * 只用接口不行 —— 首屏要等一次请求。所以先贴已有的，再拉全量。
 */
fun ReaderViewModel.openPixivArtist(user: PixivUser) {
    pixivState.openedArtist = user
    pixivState.artistWorksError = null

    // 先把已知作品放进去（立刻有东西看）
    val known = (pixivState.searchIllusts + pixivState.rankingIllusts)
        .filter { it.authorId == user.id }
        .distinctBy { it.id }
    pixivState.artistWorks.clear()
    pixivState.artistWorks.addAll(known)

    pixivState.artistWorksLoading = true
    viewModelScope.launch {
        val client = newPixivClient()
        val works = withContext(Dispatchers.IO) {
            client.userIllusts(user.id, limit = 30, mirror = settings.pixivImageMirror)
        }
        if (works.isEmpty()) {
            pixivState.artistWorksError = client.lastError?.toUiMessage()
                ?: if (known.isEmpty()) "这个画师暂时没有公开作品" else null
        } else {
            pixivState.artistWorks.clear()
            pixivState.artistWorks.addAll(works)
        }
        pixivState.artistWorksLoading = false
    }
}

fun ReaderViewModel.closePixivArtist() {
    pixivState.openedArtist = null
    pixivState.artistWorks.clear()
    pixivState.artistWorksError = null
}

// ---------------- 大图浏览器 ----------------

fun ReaderViewModel.openPixivViewer(list: List<PixivIllust>, index: Int) {
    pixivState.viewerIllusts = list
    pixivState.viewerIndex = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
}

fun ReaderViewModel.closePixivViewer() {
    pixivState.viewerIllusts = emptyList()
    pixivState.viewerIndex = 0
}

/**
 * 保存当前大图到相册。
 *
 * 走 MediaStore（Android 10+ 唯一免权限写相册的通道），
 * 并尝试把缩略地址还原成原图地址 —— 详见 [PixivSaver.originalCandidates]。
 */
fun ReaderViewModel.savePixivImage(url: String) {
    if (url.isBlank()) return
    viewModelScope.launch {
        val hint = pixivState.viewerIllusts
            .getOrNull(pixivState.viewerIndex)?.title.orEmpty()
        val r = com.ayanami.reader.core.pixiv.PixivSaver.save(
            getApplication(), url, hint
        )
        r.onSuccess { toast("已保存到相册：$it") }
            .onFailure { toast("保存失败：${it.message}") }
    }
}

// ---------------- 小说阅读 ----------------

/**
 * 打开一篇 Pixiv 小说。
 *
 * 拉正文 → 用 [PixivNovelParser] 拆章 → 塞进现有的阅读器状态。
 * 复用 `ReaderUiState.chapter/toc`，所以字号、翻页、插图、
 * 阅读主题全部自动继承，不需要为 Pixiv 再写一个阅读页。
 */
fun ReaderViewModel.openPixivNovel(novel: PixivNovel) {
    pixivState.readingNovel = novel
    pixivState.readingLoading = true
    pixivState.readingText = null

    viewModelScope.launch {
        val client = newPixivClient()
        val raw = withContext(Dispatchers.IO) { client.novelText(novel.id) }
        if (raw.isNullOrBlank()) {
            pixivState.readingLoading = false
            toast(
                client.lastError?.toUiMessage()
                    ?: "拿不到这篇小说的正文（可能仅登录可见）"
            )
            return@launch
        }

        val parsed = withContext(Dispatchers.IO) { PixivNovelParser.parse(raw) }
        val chapters = parsed.sections.mapIndexed { i, s ->
            com.ayanami.reader.core.model.BookChapter(
                title = s.title ?: novel.title,
                url = "pixiv://novel/${novel.id}#$i",
                index = i
            )
        }

        // 把整篇按 section 切片缓存在内存里，供 loadChapter 取
        pixivNovelSections = parsed.sections.map { it.body }

        _state.value = _state.value.copy(
            openedBook = com.ayanami.reader.core.model.Book(
                name = novel.title,
                author = novel.authorName,
                intro = novel.description,
                coverUrl = novel.coverUrl,
                bookUrl = "pixiv://novel/${novel.id}",
                origin = "pixiv",
                originName = "Pixiv"
            ),
            toc = chapters,
            chapterIndex = 0,
            chapter = com.ayanami.reader.core.model.TextChapter(
                title = parsed.sections.firstOrNull()?.title ?: novel.title,
                url = "pixiv://novel/${novel.id}#0",
                text = parsed.sections.firstOrNull()?.body.orEmpty()
            )
        )
        pixivState.readingLoading = false
    }
}

/**
 * Pixiv 小说的正文切片缓存。
 *
 * 为什么放这里而不是走 DownloadEngine：Pixiv 小说一次请求就拿回全文，
 * 分章只是**本地切分**，没有任何网络成本。走下载引擎要建目录、
 * 写文件、按 bookUrl 哈希 —— 为了几百 KB 的文本做这些不值得。
 */
private var pixivNovelSections: List<String> = emptyList()

/** 取 Pixiv 小说某一章（纯内存，不走网络）。 */
fun ReaderViewModel.loadPixivChapter(index: Int) {
    val sections = pixivNovelSections
    if (index !in sections.indices) return
    val novel = pixivState.readingNovel ?: return
    val toc = _state.value.toc
    _state.value = _state.value.copy(
        chapterIndex = index,
        chapter = com.ayanami.reader.core.model.TextChapter(
            title = toc.getOrNull(index)?.title ?: novel.title,
            url = "pixiv://novel/${novel.id}#$index",
            text = sections[index]
        )
    )
}

/** 这个书是不是 Pixiv 来源（阅读页据此走内存取章而不是走书源）。 */
fun ReaderUiState.isPixivNovel(): Boolean =
    openedBook?.origin == "pixiv"

// ---------------- 搜索 ----------------

/**
 * Pixiv 搜索（供搜索页的 Pixiv 子 Tab 用）。
 *
 * @param mode 决定把结果放进 illusts / novels / users 哪个桶
 */
fun ReaderViewModel.pixivSearchImages(keyword: String) {
    pixivSearch(keyword, PixivSearchMode.ILLUST)
}

/**
 * Pixiv 搜索。
 *
 * ⚠️ 只写**搜索侧**状态（`searchIllusts` / `searchNovels` / `searchUsers`），
 * 绝不碰榜单状态 —— v0.7.0 两边共用同一份列表，搜完插画切回 Pixiv
 * 发现榜单被替换成 48 条搜索结果，那是明显的串台。
 *
 * @param mode 决定结果进哪个桶
 */
fun ReaderViewModel.pixivSearch(keyword: String, mode: PixivSearchMode) {
    if (keyword.isBlank()) {
        clearPixivSearch()
        return
    }
    pixivState.keyword = keyword
    pixivState.searchedKeyword = keyword
    pixivState.searchLoading = true
    pixivState.searchError = null

    viewModelScope.launch {
        val client = newPixivClient()
        val adultOn = settings.adultEnabled

        when (mode) {
            PixivSearchMode.ILLUST -> {
                val r = withContext(Dispatchers.IO) {
                    client.search(keyword, filterAdult = !adultOn)
                }
                pixivState.searchIllusts.clear()
                pixivState.searchIllusts.addAll(r.illusts)
                pixivState.searchTags.clear()
                pixivState.searchTags.addAll(r.relatedTags)
                client.lastError?.let { pixivState.searchError = it.toUiMessage() }
            }
            PixivSearchMode.NOVEL -> {
                val r = withContext(Dispatchers.IO) {
                    client.search(keyword, filterAdult = !adultOn)
                }
                pixivState.searchNovels.clear()
                pixivState.searchNovels.addAll(
                    r.novels.map {
                        it.copy(coverUrl = PixivImageUrls
                            .rewrite(it.coverUrl, settings.pixivImageMirror))
                    }
                )
                pixivState.searchTags.clear()
                pixivState.searchTags.addAll(r.relatedTags)
                client.lastError?.let { pixivState.searchError = it.toUiMessage() }
            }
            PixivSearchMode.USER -> {
                val list = withContext(Dispatchers.IO) { client.searchUsers(keyword) }
                pixivState.searchUsers.clear()
                pixivState.searchUsers.addAll(list)
                client.lastError?.let { pixivState.searchError = it.toUiMessage() }
            }
        }
        pixivState.searchLoading = false
        pixivState.searchLoadedOnce = true
    }
}

/**
 * 清空搜索结果（搜索框清空时调用）。
 *
 * 清空后 UI 回到「2 列 Tag 墙」的推荐态。
 */
fun ReaderViewModel.clearPixivSearch() {
    pixivState.keyword = ""
    pixivState.resetSearch()
}

/**
 * Tab 切换时按需加载（只影响榜单侧）。
 *
 * ⚠️ 这里必须能识别「当前内容类型还没有数据」。
 *
 * 踩过的坑：`loadPixivRanking()` 有 `rankingLoadedOnce` 守卫，
 * 而 `rankingLoadedOnce` 是**全局一个**（不分插画/小说）。
 * 于是「先看插画榜 → 切到小说类型」时守卫直接 return，
 * 小说区永远是空的，界面上写「这个榜单暂时没拿到小说」。
 *
 * 所以这里改成：按当前内容类型判断它自己有没有数据，
 * 没有就强制拉一次。
 */
fun ReaderViewModel.ensurePixivTabLoaded() {
    when (pixivState.tab) {
        PixivTab.RANKING -> {
            val empty = if (pixivState.contentType == PixivContentType.NOVEL) {
                pixivState.rankingNovels.isEmpty()
            } else {
                pixivState.rankingIllusts.isEmpty()
            }
            loadPixivRanking(force = empty)
        }
        PixivTab.NOVEL -> {
            // 「小说」Tab 独立于内容类型选择器，直接看小说数据在不在
            if (pixivState.contentType != PixivContentType.NOVEL) {
                pixivState.contentType = PixivContentType.NOVEL
                pixivState.resetRanking()
            }
            loadPixivRanking(force = pixivState.rankingNovels.isEmpty())
        }
        PixivTab.TAGS -> loadPixivTags()
        PixivTab.USERS -> Unit  // 画师要有关键词才有内容
    }
}

// ---------------- UI 桥接辅助 ----------------

/**
 * 切换榜单（安全版）。
 *
 * `setPixivRank` 定义在别处，这里只是给它加一个不重载的入口，
 * 避免 UI 层直接拼 `resetRanking() + loadPixivRanking()` 两句话。
 */
fun ReaderViewModel.setPixivRankSafe(mode: PixivRankMode, type: PixivContentType) {
    setPixivRank(mode, type)
}

/**
 * 请求打开小说阅读页。
 *
 * `openPixivNovel` 已经在 viewModelScope 里异步拉正文，
 * UI 需要在**拉起之后**立刻导航（不能等正文回来，那要 1-2 秒）。
 * 用一个计数器让 Composable 侧 `LaunchedEffect` 能观察到"该跳了"。
 */
var ReaderViewModel.pendingNovelOpen: Boolean
    get() = PixivOpenSignal.pendingNovel
    set(v) { PixivOpenSignal.pendingNovel = v }

internal object PixivOpenSignal {
    var pendingNovel by androidx.compose.runtime.mutableStateOf(false)
}

/** 标记「现在应该打开阅读页」。 */
fun ReaderViewModel.requestOpenNovel() {
    PixivOpenSignal.pendingNovel = true
}

/** UI 消费掉这个信号。 */
fun ReaderViewModel.consumeOpenNovelSignal() {
    PixivOpenSignal.pendingNovel = false
}

/** 当前画师的作品（给 UI 读，避免直接访问内部列表）。 */
val ReaderViewModel.artistWorksOf: List<PixivIllust>
    get() = pixivState.artistWorks
