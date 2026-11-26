package com.ayanami.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookChapter
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.model.TextChapter
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceImporter
import com.ayanami.reader.core.pipeline.SourceSession
import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.download.DownloadEngine
import com.ayanami.reader.core.net.HostGate
import com.ayanami.reader.data.Repository
import com.ayanami.reader.data.Settings
import com.ayanami.reader.mod.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 跨源搜索结果聚合项（同一本书在多个源的命中）。 */
data class AggregatedHit(
    val name: String,
    val author: String,
    val books: List<SearchBook>
) {
    val origins: String get() = books.joinToString(" / ") { it.originName }
}

data class ReaderUiState(
    val sources: List<BookSource> = emptyList(),
    val checks: Map<String, SourceChecker.CheckResult> = emptyMap(),
    val failureCounts: Map<String, Int> = emptyMap(),
    val keyword: String = "",
    val searching: Boolean = false,
    val hits: List<AggregatedHit> = emptyList(),
    val searchErrors: List<Pair<String, String>> = emptyList(),
    /** 本次搜索实际发起了几个源（跳过的源不计入）。 */
    val searchSourceCount: Int = 0,
    /** 因体检失败/未验证而被跳过的源数。 */
    val searchSkippedCount: Int = 0,
    /** 已被 R18 硬过滤掉的书名数（让用户知道过滤真的生效了）。 */
    val searchAdultFilteredCount: Int = 0,
    val openedBook: Book? = null,
    val toc: List<BookChapter> = emptyList(),
    val loadingToc: Boolean = false,
    val chapter: TextChapter? = null,
    val loadingChapter: Boolean = false,
    val chapterIndex: Int = -1,
    val message: String? = null,
    val checkingAll: Boolean = false,
    val checkProgress: Pair<Int, Int>? = null
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    internal val repo = Repository(app)
    internal val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    var mods: ModLoader.LoadResult = ModLoader.load(emptyList()) { null }
    var activeModTheme: ModLoader.ThemeTokens? = null
    var modErrors: List<String> = emptyList()

    /** 全局设置（R18 开关、阅读器偏好、Pixiv 配置）。 */
    val settings = Settings(app)

    /** 章节离线缓存引擎。缓存目录在 app 私有 files 下。 */
    val download = DownloadEngine(
        viewModelScope,
        java.io.File(app.filesDir, "cache")
    )

    /** 书城聚合引擎。 */
    val bookstoreEngine = BookstoreEngine(viewModelScope)

    /** 书架「最近阅读」列表。 */
    private val recentReadingState =
        androidx.compose.runtime.mutableStateOf<List<RecentReading>>(emptyList())

    val recentReading: List<RecentReading> get() = recentReadingState.value

    internal fun setRecentReading(list: List<RecentReading>) {
        recentReadingState.value = list
    }

    /**
     * 当前搜索任务。
     *
     * 有了它才能「重新搜索时取消上一次」—— 否则用户改关键词连按两次，
     * 上一次的慢源结果会覆盖这一次的，看起来像搜错了。
     */
    private var searchJob: Job? = null

    /** Pixiv 页 UI 状态（标签、榜单、大图、小说）。 */
    val pixivState = PixivUiState()

    /**
     * 搜索页当前子 Tab。
     *
     * 放在 VM 而不是 Composable 局部 state —— Pixiv 页点 Tag 需要
     * 「跳到搜索页 + 切到插画 Tab + 填入关键词」，三件事必须原子地一起发生。
     */
    var searchTab by androidx.compose.runtime.mutableStateOf(SearchTab.SOURCE)

    /**
     * 从其他页面发起一次 Pixiv 搜索（点 Tag、点相关标签用）。
     *
     * 一步做三件事：切 Tab、写关键词、发起搜索。
     * 这样调用方只需要 `openPixivSearch("VOCALOID")`，不会漏掉任何一步。
     */
    fun openPixivSearch(
        keyword: String,
        mode: PixivSearchMode = PixivSearchMode.ILLUST
    ) {
        searchTab = when (mode) {
            PixivSearchMode.ILLUST -> SearchTab.PIXIV_ILLUST
            PixivSearchMode.NOVEL -> SearchTab.PIXIV_NOVEL
            PixivSearchMode.USER -> SearchTab.PIXIV_USER
        }
        searchKeywordBridge = keyword
        pixivSearch(keyword, mode)
    }

    /**
     * 外部注入的关键词（供搜索页初始化输入框）。
     *
     * 用独立字段而不是 `pixivState.keyword`：后者是"上次搜了什么"，
     * 而这个是"这次要填进输入框什么"，语义不同。
     */
    var searchKeywordBridge by androidx.compose.runtime.mutableStateOf("")

    /** 书城页 UI 状态。 */
    val bookstoreState = BookstoreState().also {
        it.genre = settings.bookstoreGenre
        it.rating = settings.contentRating
        it.sort = settings.sortMode.name
    }

    /**
     * 设置变更计数 —— 用于让 Compose 感知 `SharedPreferences` 的变化。
     *
     * 为什么需要：`SharedPreferences` 不是 Compose 快照状态，改完不会触发重组。
     * 用一个自增的 Compose state 当"信号"，UI 读它就能正确刷新。
     */
    private val settingsRevisionState = androidx.compose.runtime.mutableStateOf(0)

    fun settingsTick(): Int = settingsRevisionState.value

    fun notifySettingsChanged() {
        settingsRevisionState.value = settingsRevisionState.value + 1
    }

    init {
        reloadSources()
        loadMods()
        loadRecentReading()
    }

    // ---------------- 书源 ----------------

    fun reloadSources() {
        val list = repo.loadSources()
        _state.value = _state.value.copy(
            sources = list,
            checks = repo.loadCheckResults(),
            failureCounts = repo.loadFailureCounts()
        )
    }

    fun loadMods() {
        val r = repo.loadMods()
        mods = r
        modErrors = r.errors
        activeModTheme = r.mods.firstNotNullOfOrNull { it.theme }
        // MOD 里带的书源包自动并入
        val fromMods = r.mods.mapNotNull { it.sourceJson }
        if (fromMods.isNotEmpty()) {
            fromMods.forEach { json -> runCatching { repo.importSources(json) } }
            reloadSources()
        }
    }

    fun importSources(raw: String) {
        val json = raw.trim()
        if (json.isEmpty()) {
            toast("内容为空")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repo.importSources(json) }
            reloadSources()
            val parts = buildList {
                if (result.sources.isNotEmpty()) add("导入 ${result.sources.size} 条")
                if (result.duplicates.isNotEmpty()) add("重复 ${result.duplicates.size} 条")
                if (result.invalid.isNotEmpty()) add("无效 ${result.invalid.size} 条")
                if (result.groupNames.isNotEmpty()) add("分组 ${result.groupNames.joinToString("/")}")
            }
            toast(if (parts.isEmpty()) "没有可导入的条目" else parts.joinToString("，"))
        }
    }

    fun importSourcesFromFile(path: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { java.io.File(path).readText() }.getOrNull()
            }
            if (text == null) toast("读取文件失败") else importSources(text)
        }
    }

    fun deleteSource(url: String) {
        repo.deleteSource(url)
        reloadSources()
        toast("已删除")
    }

    fun toggleSource(url: String, enabled: Boolean) {
        repo.setSourceEnabled(url, enabled)
        reloadSources()
    }

    fun installMod(path: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { repo.installModFromZip(java.io.File(path)) }
            r.onSuccess {
                loadMods()
                reloadSources()
                toast("MOD 已安装：$it")
            }.onFailure { toast("安装失败：${it.message}") }
        }
    }

    // ---------------- 体检 ----------------

    fun checkOne(source: BookSource) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SourceChecker.check(source, polite = true)
            }
            repo.saveCheckResult(result)
            _state.value = _state.value.copy(
                checks = repo.loadCheckResults(),
                failureCounts = repo.loadFailureCounts()
            )
        }
    }

    /**
     * 全量重测：**串行**跑，不为速度牺牲礼貌（方案 §3.2.5）。
     *
     * 只把「同 host 的间隔」交给 [HostGate.pace]，不再动
     * `HttpFetcher` 的全局变量 —— 那个变量以前会被设成 3000ms 却忘了复位，
     * 之后用户去读书，每一章都要多等 3 秒。
     */
    fun checkAll() {
        if (_state.value.checkingAll) return
        viewModelScope.launch {
            val list = _state.value.sources.filter { it.bookSourceEnabled }
            _state.value = _state.value.copy(checkingAll = true, checkProgress = 0 to list.size)
            for ((i, src) in list.withIndex()) {
                val r = withContext(Dispatchers.IO) {
                    HostGate.pace(src.bookSourceUrl, HostGate.checkIntervalMs) {
                        SourceChecker.check(src, polite = true)
                    }
                }
                repo.saveCheckResult(r)
                _state.value = _state.value.copy(
                    checks = repo.loadCheckResults(),
                    failureCounts = repo.loadFailureCounts(),
                    checkProgress = (i + 1) to list.size
                )
            }
            _state.value = _state.value.copy(checkingAll = false, checkProgress = null)
            toast("体检完成：${repo.loadCheckResults().values.count {
                it.status == SourceChecker.UiStatus.READABLE
            }}/${list.size} 可读")
        }
    }

    // ---------------- 搜索 ----------------

    /**
     * 跨源搜索。
     *
     * ### 并发策略
     *
     * 按域名分组并发（[HostGate.run]）：同域名串行 + 800ms 礼貌间隔，
     * 跨域名最多 4 个同时在跑。这跟 v0.6.0 的 `for (src in enabled)` 串行
     * 有本质差别 —— 20 个源的总耗时从「累加」变成「最慢那一档」。
     *
     * 为什么必须按域名而不是无脑铺开：大量书源其实同属一个站
     * （同一域名下不同频道），并发打同一个站既是找封，也没意义。
     *
     * ### 跳过无效源
     *
     * 体检明确失败（连接失败 / 需登录 / 不支持）的源直接跳过，
     * 不再浪费时间等它超时。未体检（UNVERIFIED）的源仍然搜 ——
     * 很多人导入书源就直接用，不该逼他们先跑体检。
     */
    fun search(keyword: String) {
        if (keyword.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                keyword = keyword,
                searching = true,
                hits = emptyList(),
                searchErrors = emptyList(),
                searchSourceCount = 0,
                searchSkippedCount = 0,
                searchAdultFilteredCount = 0
            )

            val all = _state.value.sources.filter { it.bookSourceEnabled }
            val skipCount = _state.value.checks.count { (url, r) ->
                all.any { it.bookSourceUrl == url } && r.status.isHardFailure
            }
            val enabled = all.filter { src ->
                _state.value.checks[src.bookSourceUrl]?.status?.isHardFailure != true
            }

            val raw = ArrayList<SearchBook>()
            val errors = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())

            // 按域名分组：同一 host 的源在同一个协程里顺序跑
            val byHost = enabled.groupBy { HostGate.hostOf(it.bookSourceUrl) }
            coroutineScope {
                byHost.map { (_, group) ->
                    async(Dispatchers.IO) {
                        for (src in group) {
                            val gateUrl = src.bookSourceUrl.ifBlank { "unknown://${src.bookSourceName}" }
                            val s = SourceSession(src, polite = true)
                            val r = runCatching {
                                HostGate.run(gateUrl, HostGate.searchIntervalMs) {
                                    s.search(keyword)
                                }
                            }.getOrElse { emptyList() }
                            if (r.isEmpty()) {
                                errors.add(src.bookSourceName to (s.lastError?.humanMessage ?: "无结果"))
                            } else {
                                synchronized(raw) { raw.addAll(r) }
                            }
                        }
                    }
                }.awaitAll()
            }

            val adultOn = settings.adultEnabled
            val filteredAdult = java.util.concurrent.atomic.AtomicInteger(0)

            // 跨源聚合：同名+同作者 视为同一本。
            // R18 硬过滤在**聚合之前**做 —— 否则关了开关还能从「其他源」
            // 那一栏看到成人书名。
            val grouped = LinkedHashMap<String, MutableList<SearchBook>>()
            raw.forEach { b ->
                if (!adultOn) {
                    val cls = BookClassifier.classify(
                        name = b.name,
                        author = b.author,
                        intro = b.intro,
                        kindRaw = b.kind,
                        sourceGroup = _state.value.sources
                            .firstOrNull { it.bookSourceUrl == b.origin }?.bookSourceGroup
                    )
                    if (cls.isAdult) {
                        filteredAdult.incrementAndGet()
                        return@forEach
                    }
                }
                grouped.getOrPut(b.stableKey) { ArrayList() }.add(b)
            }
            val hits = grouped.values.map { list ->
                val first = list.first()
                AggregatedHit(first.name, first.author, list)
            }

            _state.value = _state.value.copy(
                searching = false,
                hits = hits,
                searchErrors = errors.toList(),
                searchSourceCount = enabled.size,
                searchSkippedCount = skipCount,
                searchAdultFilteredCount = filteredAdult.get()
            )
        }
    }

    // ---------------- 打开书 ----------------

    fun openHit(hit: AggregatedHit, pickOriginIndex: Int = 0) {
        val b = hit.books.getOrNull(pickOriginIndex) ?: hit.books.firstOrNull() ?: return
        val src = _state.value.sources.firstOrNull { it.bookSourceUrl == b.origin }
        if (src == null) {
            toast("找不到对应书源")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingToc = true, toc = emptyList(), openedBook = null)
            val info = withContext(Dispatchers.IO) { SourceSession(src).bookInfo(b.bookUrl) }
            if (info == null) {
                _state.value = _state.value.copy(loadingToc = false)
                toast("详情页抓取失败")
                return@launch
            }
            val toc = withContext(Dispatchers.IO) { SourceSession(src).toc(info) }
            _state.value = _state.value.copy(
                loadingToc = false,
                openedBook = if (info.name.isBlank()) null else info,
                toc = toc,
                chapter = null,
                chapterIndex = -1
            )
            if (toc.isEmpty()) toast("目录为空")
        }
    }

    fun loadChapter(chapter: BookChapter, index: Int) {
        // 缓存优先：命中就完全不发请求（离线阅读的全部实现）
        loadChapterWithCache(chapter, index)
    }

    /** 真正联网抓正文。外部统一走 loadChapter（它先查缓存）。 */
    fun loadChapterNetwork(chapter: BookChapter, index: Int) {
        val book = _state.value.openedBook ?: return
        val src = _state.value.sources.firstOrNull { it.bookSourceUrl == book.origin } ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingChapter = true, chapterIndex = index)
            val text = withContext(Dispatchers.IO) { SourceSession(src).content(chapter) }
            if (text == null) {
                _state.value = _state.value.copy(loadingChapter = false)
                toast("正文抓取失败")
                return@launch
            }
            repo.saveProgress(book.bookUrl, index, chapter.title, book.name)
            loadRecentReading()
            _state.value = _state.value.copy(loadingChapter = false, chapter = text)
        }
    }

    fun toast(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
