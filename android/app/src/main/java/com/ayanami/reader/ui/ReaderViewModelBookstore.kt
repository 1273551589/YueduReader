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

/**
 * 书城首屏加载。
 *
 * ### 顺序很重要
 *
 * 1. **先贴快照**（有的话）→ 用户立刻看到内容，不是空列表转圈
 * 2. **再后台刷新** → 拉真实数据，成功后覆盖 + 更新快照
 *
 * 快照过期（>30min）也先用：过期的书城远好过一片空白。
 * 刷新失败时保留快照，不清空 —— 断网时还能逛上次的内容。
 */
fun ReaderViewModel.loadBookstore(force: Boolean = false) {
    if (bookstoreState.loading) return
    if (bookstoreState.initialized && !force) return

    bookstoreState.loading = true
    bookstoreState.errors = emptyList()
    viewModelScope.launch {
        // ---- 1) 快照垫底 ----
        val snapshot = withContext(Dispatchers.IO) {
            com.ayanami.reader.core.bookstore.BookstoreSnapshot.load(repo.cacheDir)
        }
        if (snapshot != null && bookstoreState.allBooks.isEmpty()) {
            bookstoreState.setBooks(snapshot.books, true)
            // 标记"现在显示的是缓存"，让 UI 可以说一句
            bookstoreState.fromSnapshot = !snapshot.isFresh
        }

        val usable = withContext(Dispatchers.IO) {
            bookstoreEngine.usableSources(
                sources = state.value.sources,
                checks = state.value.checks
            )
        }
        bookstoreState.updateSources(usable)

        if (usable.isEmpty()) {
            // 有快照就留着，没有才清空
            if (bookstoreState.allBooks.isEmpty()) {
                bookstoreState.setBooks(emptyList(), false)
            }
            bookstoreState.loading = false
            bookstoreState.initialized = true
            return@launch
        }

        val page = withContext(Dispatchers.IO) {
            val p = bookstoreEngine.loadPage(
                sources = usable,
                adultOn = adultEnabled,
                perSource = 20,
                firstPage = true
            )
            if (p.books.isNotEmpty()) {
                com.ayanami.reader.core.bookstore.BookstoreSnapshot.save(repo.cacheDir, p.books)
            }
            p
        }

        if (page.books.isNotEmpty()) {
            bookstoreState.setBooks(page.books, page.hasMore)
            bookstoreState.fromSnapshot = false
        }
        // 刷新回来是空的 → **保留快照内容**，不要把用户正在看的东西清掉
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
    // ⚠️ 必须带上 altOrigins —— 否则书城打开的书永远只有一个源，
    // 详情页的「换源」入口就是空的，用户还得回搜索页重新搜一遍。
    val hit = AggregatedHit(
        name = book.book.name,
        author = book.book.author,
        books = listOf(book.book) + book.altOrigins.filter { it.origin != book.book.origin }
    )
    // 把聚合结果挂进 state.hits，让详情页的 alternateSources() 找得到
    _state.value = _state.value.copy(
        hits = (_state.value.hits.filterNot { it.name == hit.name && it.author == hit.author } + hit)
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
            for (src in checking) {
                val r = withContext(Dispatchers.IO) {
                    com.ayanami.reader.core.net.HostGate.pace(
                        src.bookSourceUrl,
                        com.ayanami.reader.core.net.HostGate.checkIntervalMs
                    ) { SourceChecker.check(src, polite = true) }
                }
                repo.saveCheckResult(r)
            }
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
