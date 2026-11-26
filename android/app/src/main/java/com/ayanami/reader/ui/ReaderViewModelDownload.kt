package com.ayanami.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ayanami.reader.core.download.DownloadEngine
import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ReaderViewModel] 的下载 / 离线 / 跨源切换扩展。
 */

// ---------------- 下载 ----------------

/** 当前书的下载任务（null = 没在下载）。 */
private val ReaderViewModel.downloadTasks
    get() = DownloadHolder.tasks

/** 内部持有下载任务状态，避免给主 VM 再加一堆字段。 */
internal object DownloadHolder {
    var tasks by mutableStateOf<Map<String, DownloadEngine.Task>>(emptyMap())
}

/** UI 读这个判断当前书下到哪了。 */
fun ReaderViewModel.downloadTask(bookUrl: String): DownloadEngine.Task? =
    downloadTasks[download.bookKey(bookUrl)]

/** 已缓存章数。 */
fun ReaderViewModel.cachedCount(book: Book, total: Int): Int =
    download.cachedCount(book.bookUrl, total)

/**
 * 开始缓存。
 *
 * @param mode ALL=全本 / NEXT_N=后续 N 章 / RANGE=自定义范围
 */
fun ReaderViewModel.startDownload(
    book: Book,
    toc: List<BookChapter>,
    mode: DownloadMode,
    from: Int = 0,
    count: Int = 50
) {
    val src = state.value.sources.firstOrNull { it.bookSourceUrl == book.origin }
    if (src == null) {
        toast("找不到对应书源")
        return
    }
    if (toc.isEmpty()) {
        toast("目录为空，先打开书")
        return
    }

    val (ranges, maxCount) = when (mode) {
        DownloadMode.ALL -> null to null
        DownloadMode.NEXT_N -> {
            // 「后续 N 章」= 从当前阅读位置往后 N 章
            val start = state.value.chapterIndex.coerceAtLeast(0)
            (start until (start + count)).toList() to null
        }
        DownloadMode.RANGE -> (from until (from + count)).toList() to null
    }

    val task = download.start(
        source = src,
        book = book,
        toc = toc,
        ranges = ranges,
        maxCount = maxCount
    ) { t ->
        DownloadHolder.tasks = DownloadHolder.tasks + (t.bookKey to t)
    }
    DownloadHolder.tasks = DownloadHolder.tasks + (task.bookKey to task)

    when (task.state) {
        DownloadEngine.Task.State.DONE -> toast("全部章节已缓存")
        else -> toast("开始缓存 ${task.total} 章")
    }
}

fun ReaderViewModel.pauseDownload(book: Book) {
    download.pause(book.bookUrl)
    toast("已暂停，已下载的章节会保留")
}

// ---------------- 离线优先读取 ----------------

/**
 * 读一章：**先查本地缓存，没有再联网**。
 *
 * 这就是「离线阅读」的全部实现 —— 不需要单独的离线模式开关，
 * 缓存里有的章节就永远不会发请求。
 */
fun ReaderViewModel.loadChapterWithCache(chapter: BookChapter, index: Int) {
    val book = state.value.openedBook ?: return
    val bookUrl = book.bookUrl

    // 先命缓存
    val cached = download.readCached(bookUrl, index)
    if (cached != null) {
        val title = cached.substringBefore('\n')
        val body = cached.substringAfter('\n', "")
        _state.value = _state.value.copy(
            loadingChapter = false,
            chapterIndex = index,
            chapter = com.ayanami.reader.core.model.TextChapter(
                title = title.ifBlank { chapter.title },
                url = chapter.url,
                text = body
            )
        )
        repo.saveProgress(bookUrl, index, chapter.title, book.name)
        loadRecentReading()
        return
    }

    // 没缓存 → 走联网。⚠️ 必须调 loadChapterNetwork 而不是 loadChapter ——
    // 后者会再转回 loadChapterWithCache，直接无限递归直到 StackOverflow
    loadChapterNetwork(chapter, index)
}

/**
 * 导出已缓存章节为 TXT。
 *
 * 落到 `Android/data/com.ayanami.reader/files/exports/` —— 免存储权限，
 * 用户能从文件管理器取出来。
 */
fun ReaderViewModel.exportTxt(book: Book, toc: List<BookChapter>) {
    viewModelScope.launch {
        val out = withContext(Dispatchers.IO) {
            val dir = File(getApplication<android.app.Application>().getExternalFilesDir(null), "exports")
            val safeName = book.name.replace(Regex("""[/\\:*?"<>|]"""), "_").take(60)
            val target = File(dir, "${safeName}.txt")
            download.exportTxt(book, toc, target)
        }
        out.onSuccess { n ->
            toast("已导出 $n 章")
        }.onFailure {
            toast("导出失败：${it.message}")
        }
    }
}

// ---------------- 跨源切换 ----------------

/**
 * 切换到另一个书源读同一本书。
 *
 * ### 为什么需要它
 *
 * 同一本书在多个源都有。某个源可能：目录缺章、正文是防盗版乱码、
 * 或者干脆某个源限速。用户不该为此重新搜一遍。
 *
 * ### 实现上的坑
 *
 * **不能直接拿当前书源去查另一个源的地址** —— 每个源的 bookUrl 格式完全不同。
 * 所以必须用「书名 + 作者」去目标源重新搜一次，取匹配度最高的结果。
 */
fun ReaderViewModel.switchToSource(
    hit: AggregatedHit,
    targetSourceUrl: String,
    currentChapterIndex: Int
) {
    val target = hit.books.firstOrNull { it.origin == targetSourceUrl }
    if (target == null) {
        toast("这个源里没有这本书")
        return
    }
    val src = state.value.sources.firstOrNull { it.bookSourceUrl == targetSourceUrl }
    if (src == null) {
        toast("书源已被删除")
        return
    }

    viewModelScope.launch {
        _state.value = _state.value.copy(loadingToc = true)
        val info = withContext(Dispatchers.IO) { com.ayanami.reader.core.pipeline.SourceSession(src).bookInfo(target.bookUrl) }
        if (info == null) {
            _state.value = _state.value.copy(loadingToc = false)
            toast("目标源详情页抓取失败")
            return@launch
        }
        val toc = withContext(Dispatchers.IO) { com.ayanami.reader.core.pipeline.SourceSession(src).toc(info) }
        if (toc.isEmpty()) {
            _state.value = _state.value.copy(loadingToc = false)
            toast("目标源目录为空")
            return@launch
        }

        // 尽量停在原来的章节：按标题匹配，匹配不上就按进度比例估算
        val keepTitle = state.value.chapter?.title ?: state.value.toc.getOrNull(currentChapterIndex)?.title
        val newIndex = if (keepTitle != null) {
            toc.indexOfFirst { it.title.trim() == keepTitle.trim() }.takeIf { it >= 0 }
                ?: estimateIndex(currentChapterIndex, state.value.toc.size, toc.size)
        } else {
            estimateIndex(currentChapterIndex, state.value.toc.size, toc.size)
        }

        _state.value = _state.value.copy(
            loadingToc = false,
            openedBook = info,
            toc = toc,
            chapterIndex = newIndex,
            chapter = null
        )
        toast("已切换到 ${src.bookSourceName}" +
            if (newIndex != currentChapterIndex) "（章节按进度对齐）" else "")
    }
}

/** 标题匹配不上时，按比例估算新源里对应的章节。 */
private fun estimateIndex(oldIndex: Int, oldTotal: Int, newTotal: Int): Int {
    if (oldTotal <= 0 || newTotal <= 0) return 0
    val ratio = oldIndex.toDouble() / oldTotal
    return (ratio * newTotal).toInt().coerceIn(0, newTotal - 1)
}

/** 当前书在其他源也有时，给出可切换的候选。 */
fun ReaderViewModel.alternateSources(): List<Pair<String, String>> {
    val book = state.value.openedBook ?: return emptyList()
    val key = "${book.name}\u0000${book.author}"
    val hit = state.value.hits.firstOrNull {
        "${it.name}\u0000${it.author}" == key
    } ?: return emptyList()
    return hit.books
        .filter { it.origin != book.origin }
        .map { it.origin to it.originName }
        .distinctBy { it.first }
}

enum class DownloadMode(val label: String) {
    ALL("缓存全本"),
    NEXT_N("缓存后续 50 章"),
    RANGE("自定义范围")
}
