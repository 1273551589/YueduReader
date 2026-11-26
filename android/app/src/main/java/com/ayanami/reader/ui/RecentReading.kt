package com.ayanami.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架上的「最近阅读」条目。
 *
 * 数据源是 `Repository.loadProgress()` —— 阅读时每章都会写进度，
 * 所以这里不需要额外记录，直接读出反序列化即可。
 */
data class RecentReading(
    /** 书籍详情页地址，作为唯一键 */
    val bookUrl: String,
    val bookName: String,
    val chapterTitle: String,
    val chapterIndex: Int,
    val updatedAt: Long
) {
    /** 人类可读的时间。"刚刚" / "3 小时前" / "11-26" */
    val timeLabel: String
        get() {
            if (updatedAt <= 0) return ""
            val diff = System.currentTimeMillis() - updatedAt
            val min = diff / 60_000
            return when {
                min < 1 -> "刚刚"
                min < 60 -> "${min} 分钟前"
                min < 60 * 24 -> "${min / 60} 小时前"
                min < 60 * 24 * 7 -> "${min / (60 * 24)} 天前"
                else -> {
                    val d = java.time.Instant.ofEpochMilli(updatedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                    "${d.monthValue}-${d.dayOfMonth}"
                }
            }
        }
}

/**
 * 载入最近阅读列表。
 *
 * 进度文件里存的是 `bookUrl -> {index, title, time}`，
 * 但**没有书名** —— 书名在详情页才知道。所以这里用 bookUrl 的最后一段
 * 当作临时标题，等用户点进去再显示真名。
 *
 * 更好的做法是在第一次保存进度时就把书名写进去。
 * 见 [Repository.saveProgress] 的新签名：现在会记 name。
 */
fun ReaderViewModel.loadRecentReading() {
    viewModelScope.launch {
        val list = withContext(Dispatchers.IO) {
            val raw = repo.loadProgress()
            raw.mapNotNull { (url, m) ->
                val idx = (m["index"] as? Number)?.toInt() ?: return@mapNotNull null
                val title = (m["title"] as? String) ?: ""
                val time = (m["time"] as? Number)?.toLong() ?: 0L
                val name = (m["name"] as? String) ?: guessNameFromUrl(url)
                RecentReading(url, name, title, idx, time)
            }.sortedByDescending { it.updatedAt }
        }
        setRecentReading(list)
    }
}

/** 从 URL 猜书名（进度文件没记 name 时的兜底）。 */
private fun guessNameFromUrl(url: String): String {
    val seg = url.trimEnd('/').substringAfterLast('/')
        .substringBefore('?')
        .substringBeforeLast('.')
    return seg.ifBlank { "未知书名" }
}

/**
 * 打开一条最近阅读记录。
 *
 * 需要先找回它属于哪个书源：进度里存的是 bookUrl，
 * 而 bookUrl 是某个源解析出来的绝对地址。
 * 用 `bookSourceUrl` 前缀匹配 —— 书源地址就是它自己站点的根。
 */
fun ReaderViewModel.openRecent(item: RecentReading) {
    val src = matchSource(item.bookUrl)
    if (src == null) {
        toast("找不到这本书对应的书源，可能已被删除")
        return
    }
    viewModelScope.launch {
        _state.value = _state.value.copy(
            loadingToc = true, toc = emptyList(), openedBook = null
        )
        val info: Book? = withContext(Dispatchers.IO) {
            SourceSession(src).bookInfo(item.bookUrl)
        }
        if (info == null) {
            _state.value = _state.value.copy(loadingToc = false)
            toast("详情页抓取失败，可能这本书已下架")
            return@launch
        }
        val toc = withContext(Dispatchers.IO) { SourceSession(src).toc(info) }
        _state.value = _state.value.copy(
            loadingToc = false,
            openedBook = info,
            toc = toc,
            chapter = null,
            chapterIndex = item.chapterIndex
        )
        if (toc.isEmpty()) toast("目录为空")
    }
}

/**
 * 按书源 URL 前缀匹配。
 *
 * 取**最长前缀**匹配 —— 同一域名下可能有多个书源（不同路径），
 * 取最长的那个才不会错配到别的源。
 */
private fun ReaderViewModel.matchSource(bookUrl: String): BookSource? =
    _state.value.sources
        .filter { it.bookSourceEnabled && bookUrl.startsWith(it.bookSourceUrl.trimEnd('/')) }
        .maxByOrNull { it.bookSourceUrl.length }
