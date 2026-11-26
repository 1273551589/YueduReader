package com.ayanami.reader.ui

import androidx.lifecycle.viewModelScope
import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.pipeline.SourceChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ReaderViewModel] 的书城 / 设置扩展。
 *
 * 拆成独立文件而不是往主类里塞 —— 主类已经有书源/体检/搜索/阅读四块，
 * 再加书城会变成 600 行。Kotlin 的扩展函数让它读起来还是同一个 VM。
 */

// ---------------- 设置 ----------------

/** 当前 R18 开关（UI 会读它决定显示哪些入口）。 */
val ReaderViewModel.adultEnabled: Boolean get() = settings.adultEnabled

/** 改 R18 开关并立刻刷新书城可见性。 */
fun ReaderViewModel.setAdultEnabled(enabled: Boolean) {
    settings.adultEnabled = enabled
    // 关掉时把已加载的成人书剔除，避免"关了还能看到"的错觉
    if (!enabled) {
        bookstoreState.allBooks = bookstoreState.allBooks.filterNot { it.classification.isAdult }
        if (bookstoreState.rating == "ADULT") bookstoreState.rating = "ALL"
        if (bookstoreState.genre == "ADULT") bookstoreState.genre = "ALL"
    }
    // 让 Compose 感知设置变化
    notifySettingsChanged()
}

/** 书城首屏加载。 */
fun ReaderViewModel.loadBookstore(force: Boolean = false) {
    if (bookstoreState.loading) return
    if (bookstoreState.initialized && !force) return

    bookstoreState.loading = true
    bookstoreState.errors = emptyList()
    viewModelScope.launch {
        val usable = withContext(Dispatchers.IO) {
            bookstoreEngine.usableSources(
                sources = state.value.sources,
                checks = state.value.checks
            )
        }
        bookstoreState.updateSources(usable)

        if (usable.isEmpty()) {
            bookstoreState.setBooks(emptyList(), false)
            bookstoreState.loading = false
            bookstoreState.initialized = true
            return@launch
        }

        val page = withContext(Dispatchers.IO) {
            bookstoreEngine.loadPage(
                sources = usable,
                adultOn = adultEnabled,
                perSource = 20,
                firstPage = true
            )
        }
        bookstoreState.setBooks(page.books, page.hasMore)
        bookstoreState.errors = page.errors
        bookstoreState.loading = false
        bookstoreState.initialized = true
    }
}

/** 滚到底部时加载下一页。 */
fun ReaderViewModel.loadMoreBookstore() {
    if (bookstoreState.loadingMore) return
    if (!bookstoreState.hasMore) return
    if (bookstoreState.sources.isEmpty()) return

    bookstoreState.loadingMore = true
    viewModelScope.launch {
        val page = withContext(Dispatchers.IO) {
            bookstoreEngine.loadPage(
                sources = bookstoreState.sources,
                adultOn = adultEnabled,
                perSource = 20,
                firstPage = false
            )
        }
        bookstoreState.appendBooks(page.books, page.hasMore)
        if (page.errors.isNotEmpty()) {
            bookstoreState.errors = bookstoreState.errors + page.errors
        }
        bookstoreState.loadingMore = false
    }
}

/**
 * 从书城打开一本书。
 *
 * 书城的 [BookstoreEngine.StoreBook] 携带的是 [SearchBook]，
 * 转成 `AggregatedHit`（单元素）即可复用既有的详情页链路 ——
 * 不需要为书城再写一套详情/目录/正文逻辑。
 */
fun ReaderViewModel.openHitFromBookstore(book: BookstoreEngine.StoreBook) {
    val hit = AggregatedHit(
        name = book.book.name,
        author = book.book.author,
        books = listOf(book.book)
    )
    openHit(hit)
}

/** 体检完成后，书城需要重新挑源。 */
fun ReaderViewModel.onChecksUpdated() {
    // 已经有内容的书城不主动清空（用户正在看），只标记可重新加载
    if (bookstoreState.allBooks.isEmpty()) bookstoreState.initialized = false
}

/**
 * 用当前源重新体检并刷新书城。
 *
 * 这是「书城空了怎么办」的正确答案：让用户点一次「刷新书城」，
 * 把体检和加载串起来，而不是让他自己去书源页一个个点。
 */
fun ReaderViewModel.refreshBookstoreWithCheck() {
    if (state.value.checkingAll) return
    viewModelScope.launch {
        val list = state.value.sources.filter { it.bookSourceEnabled }
        val checking = list.filter { src ->
            val c = state.value.checks[src.bookSourceUrl]
            c == null || c.status != SourceChecker.UiStatus.READABLE
        }
        if (checking.isNotEmpty()) {
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 2000L
            for (src in checking) {
                val r = withContext(Dispatchers.IO) {
                    SourceChecker.check(src, polite = true)
                }
                repo.saveCheckResult(r)
            }
            com.ayanami.reader.core.net.HttpFetcher.minIntervalPerHostMs = 0L
            reloadSources()
        }
        bookstoreEngine.reset()
        loadBookstore(force = true)
    }
}

/** 便捷：把书城的条目转成可打开的 SearchBook（供测试与外部用）。 */
fun BookstoreEngine.StoreBook.toSearchBook(): SearchBook = this.book

/** 书源列表里可逛的源数量，用于空状态提示。 */
fun ReaderViewModel.readableSourceCount(): Int =
    state.value.checks.values.count { it.status == SourceChecker.UiStatus.READABLE }
