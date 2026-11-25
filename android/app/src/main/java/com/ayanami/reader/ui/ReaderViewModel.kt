package com.ayanami.reader.ui

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
import com.ayanami.reader.data.Repository
import com.ayanami.reader.mod.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val repo = Repository(app)
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    var mods: ModLoader.LoadResult = ModLoader.load(emptyList()) { null }
    var activeModTheme: ModLoader.ThemeTokens? = null
    var modErrors: List<String> = emptyList()

    init {
        reloadSources()
        loadMods()
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

    /** 全量重测：**串行**跑，不为速度牺牲礼貌（方案 §3.2.5）。 */
    fun checkAll() {
        if (_state.value.checkingAll) return
        viewModelScope.launch {
            val list = _state.value.sources.filter { it.bookSourceEnabled }
            _state.value = _state.value.copy(checkingAll = true, checkProgress = 0 to list.size)
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 3000L
            for ((i, src) in list.withIndex()) {
                val r = withContext(Dispatchers.IO) { SourceChecker.check(src, polite = true) }
                repo.saveCheckResult(r)
                _state.value = _state.value.copy(
                    checks = repo.loadCheckResults(),
                    failureCounts = repo.loadFailureCounts(),
                    checkProgress = (i + 1) to list.size
                )
            }
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 0L
            _state.value = _state.value.copy(checkingAll = false, checkProgress = null)
            toast("体检完成")
        }
    }

    // ---------------- 搜索 ----------------

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                keyword = keyword, searching = true, hits = emptyList(), searchErrors = emptyList()
            )
            val enabled = _state.value.sources.filter { it.bookSourceEnabled }
            val all = ArrayList<SearchBook>()
            val errors = ArrayList<Pair<String, String>>()
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 1000L
            for (src in enabled) {
                val s = SourceSession(src, polite = true)
                val r = withContext(Dispatchers.IO) {
                    runCatching { s.search(keyword) }.getOrElse { emptyList() }
                }
                if (r.isEmpty()) {
                    errors.add(src.bookSourceName to (s.lastError?.humanMessage ?: "无结果"))
                } else all.addAll(r)
            }
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 0L

            // 跨源聚合：同名+同作者 视为同一本
            val grouped = LinkedHashMap<String, MutableList<SearchBook>>()
            all.forEach { b ->
                grouped.getOrPut(b.stableKey) { ArrayList() }.add(b)
            }
            val hits = grouped.values.mapNotNull { list ->
                val first = list.first()
                AggregatedHit(first.name, first.author, list)
            }
            _state.value = _state.value.copy(
                searching = false, hits = hits, searchErrors = errors
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
            repo.saveProgress(book.bookUrl, index, chapter.title)
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
