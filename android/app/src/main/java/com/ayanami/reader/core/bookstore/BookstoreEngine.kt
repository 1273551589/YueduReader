package com.ayanami.reader.core.bookstore

import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 书城聚合引擎。
 *
 * ### 为什么不能「一次性全量爬」
 *
 * 用户可能有几十个源，每个源发现页还有几十个分类 × N 页。
 * 全量爬会：① 把手机流量烧光 ② 触发目标站的封禁 ③ 首屏等 30 秒还没东西。
 *
 * 所以这里的分层是：
 * - **首屏**：每个可用源并发拉 1 个分类的第 1 页，凑够就返回
 * - **翻页**：用户滚到底才拉第 2、3… 页
 * - **限流**：按域名做并发闸门（同域名最多 2 并发），跨域名才并行
 *
 * ### 分类是本地算的
 *
 * 书源自带的 `kind` 不可信（大量是空规则或站内标签），
 * 所以每本书都用 [BookClassifier] 现算分类，**不信任源**。
 */
class BookstoreEngine(
    private val scope: CoroutineScope
) {

    /** 一本书在书城里的完整形态（含本地算出来的分类）。 */
    data class StoreBook(
        val book: SearchBook,
        val classification: BookClassifier.Result,
        /** 该条目来自哪个分类的发现页（便于按分类跳转） */
        val categoryHint: String = ""
    ) {
        val id: String get() = "${book.origin}\u0000${book.bookUrl}"
    }

    /** 一个可逛的书源：已通过体检 + 有发现页或能做搜索。 */
    data class StoreSource(
        val source: BookSource,
        val categories: List<Pair<String, String>>,
        val displayName: String
    )

    /** 聚合结果。 */
    data class Page(
        val books: List<StoreBook>,
        val errors: List<Pair<String, String>>,
        /** 是否还有下一页可拉 */
        val hasMore: Boolean
    )

    /** 每个源已拉到的页码（按源 URL 记）。 */
    private val pageBySource = ConcurrentHashMap<String, Int>()

    /** 同域名并发闸门：最多 2 并发，避免把站打挂。 */
    private val hostGates = ConcurrentHashMap<String, Semaphore>()

    private fun gateFor(url: String): Semaphore {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return hostGates.getOrPut(host) { Semaphore(2) }
    }

    /** 已聚合过的书（按「书名+作者」跨源去重）。 */
    private val seen = ConcurrentHashMap<String, StoreBook>()
    private val seenLock = Mutex()

    fun reset() {
        pageBySource.clear()
        seen.clear()
    }

    /**
     * 从校验结果里挑出「可以逛」的源。
     *
     * 只看 `READABLE` —— 这是 S 章节反复强调的：未通过体检的源塞进书城，
     * 用户点进去就是空的，体验比没有还差。
     */
    fun usableSources(
        sources: List<BookSource>,
        checks: Map<String, SourceChecker.CheckResult>
    ): List<StoreSource> = sources.mapNotNull { src ->
        if (!src.bookSourceEnabled) return@mapNotNull null
        val check = checks[src.bookSourceUrl] ?: return@mapNotNull null
        if (check.status != SourceChecker.UiStatus.READABLE) return@mapNotNull null

        val session = SourceSession(src)
        val cats = session.exploreCategories()
        // 有发现页就按发现页逛；没有发现页则降级用搜索（分类名为「全部」）
        val usable = if (cats.isNotEmpty()) cats else emptyList()
        if (usable.isEmpty() && src.searchUrl.isNullOrBlank()) return@mapNotNull null
        StoreSource(src, usable, src.bookSourceName.ifBlank { "未命名源" })
    }

    /**
     * 加载「一页」书城内容。
     *
     * @param sources     可逛的源
     * @param adultOn     R18 开关（关掉时成人书直接丢弃，不进结果）
     * @param perSource   每个源取几条
     * @param firstPage   是否从第 1 页开始（否则接着已拉到的页码往后）
     */
    suspend fun loadPage(
        sources: List<StoreSource>,
        adultOn: Boolean,
        perSource: Int = 20,
        firstPage: Boolean = false
    ): Page = coroutineScope {
        if (firstPage) reset()
        val errors = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val collected = java.util.Collections.synchronizedList(ArrayList<StoreBook>())

        val jobs = sources.map { store ->
            async(Dispatchers.IO) {
                try {
                    fetchOne(store, adultOn, perSource, firstPage, errors, collected)
                } catch (e: Exception) {
                    errors.add(store.displayName to (e.message ?: "抓取异常"))
                }
            }
        }
        jobs.awaitAll()

        // 跨源去重：同一本书在多个源出现时只留一条。
        // 保留**首次出现**的那条 —— 先到的源往往封面/简介更完整。
        val merged = ArrayList<StoreBook>()
        val addedIds = HashSet<String>()
        for (b in collected) {
            val key = "${b.book.name}\u0000${b.book.author}"
            val existing = seenLock.withLock { seen[key] }
            if (existing == null) {
                seenLock.withLock { seen[key] = b }
                if (addedIds.add(b.id)) merged.add(b)
            } else if (addedIds.add(existing.id)) {
                merged.add(existing)
            }
        }

        val hasMore = pageBySource.isNotEmpty() && sources.any { st ->
            (pageBySource[st.source.bookSourceUrl] ?: 0) < MAX_PAGES
        }

        Page(books = merged.distinctBy { it.id }, errors = errors.toList(), hasMore = hasMore)
    }

    private suspend fun fetchOne(
        store: StoreSource,
        adultOn: Boolean,
        perSource: Int,
        firstPage: Boolean,
        errors: MutableList<Pair<String, String>>,
        out: MutableList<StoreBook>
    ) = withContext(Dispatchers.IO) {
        val src = store.source
        val session = SourceSession(src, polite = true)

        val page = if (firstPage) 1 else (pageBySource[src.bookSourceUrl] ?: 0) + 1
        if (page > MAX_PAGES) return@withContext

        // 选一个分类：优先和上次不同的，避免每页都拉同一个分类
        val cat: Pair<String, String>? = store.categories.getOrNull((page - 1) % store.categories.size.coerceAtLeast(1))
        val url = cat?.second

        val raw: List<SearchBook> = if (url != null) {
            gateFor(src.bookSourceUrl).withPermit {
                session.explore(url, page)
            }
        } else {
            // 没有发现页的源：用搜索兜底（关键词轮换，尽量凑出不同的书）
            val kw = FALLBACK_KEYWORDS[(page - 1) % FALLBACK_KEYWORDS.size]
            gateFor(src.bookSourceUrl).withPermit {
                session.search(kw, page)
            }
        }

        pageBySource[src.bookSourceUrl] = page

        if (raw.isEmpty()) {
            session.lastError?.let { errors.add(store.displayName to it.humanMessage) }
            return@withContext
        }

        val cnt = AtomicInteger(0)
        for (b in raw) {
            if (cnt.get() >= perSource) break
            // 书名或地址不全的条目直接丢 —— 后续点击会失败
            if (b.name.isBlank() || b.bookUrl.isBlank()) continue

            val cls = BookClassifier.classify(
                name = b.name,
                author = b.author,
                intro = b.intro,
                kindRaw = b.kind,
                sourceGroup = src.bookSourceGroup
            )
            // R18 硬过滤：未开开关时成人书绝不进结果
            if (!adultOn && cls.isAdult) continue

            cnt.incrementAndGet()
            out.add(StoreBook(b, cls, cat?.first ?: ""))
        }
    }

    /** 按主分类筛选（`ALL` 返回全部）。 */
    fun filterByGenre(books: List<StoreBook>, genre: String?): List<StoreBook> {
        if (genre.isNullOrBlank() || genre == "ALL") return books
        val target = runCatching { BookClassifier.Genre.valueOf(genre) }.getOrNull() ?: return books
        return books.filter { it.classification.genre == target }
    }

    /** 按内容分级筛选。 */
    fun filterByRating(books: List<StoreBook>, rating: String): List<StoreBook> =
        when (rating) {
            "GENERAL" -> books.filter { !it.classification.isAdult }
            "ADULT" -> books.filter { it.classification.isAdult }
            else -> books
        }

    /** 按书源筛选。 */
    fun filterBySource(books: List<StoreBook>, sourceUrl: String?): List<StoreBook> {
        if (sourceUrl.isNullOrBlank() || sourceUrl == "ALL") return books
        return books.filter { it.book.origin == sourceUrl }
    }

    /** 排序。 */
    fun sort(books: List<StoreBook>, mode: String): List<StoreBook> = when (mode) {
        "WORD_COUNT_DESC" -> books.sortedWith(
            compareByDescending { parseWordCount(it.book.wordCount) }
        )
        "WORD_COUNT_ASC" -> books.sortedWith(
            compareBy { parseWordCount(it.book.wordCount) }
        )
        "RECENT" -> books.sortedByDescending { parseTime(it.book.updateTime) }
        else -> books
    }

    /**
     * 解析字数。
     *
     * 书源给的格式五花八门：`1234567`、`123万`、`123.4万字`、`12千字`、
     * `连载中`（非数字）。解析不出来返回 -1，排序时会沉到最后。
     */
    fun parseWordCount(raw: String?): Long {
        val s = raw?.trim() ?: return -1
        if (s.isEmpty()) return -1
        val m = Regex("""([\d.]+)\s*([万亿千]?)""").find(s) ?: return -1
        val num = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2]) {
            "万" -> 10_000L
            "亿" -> 100_000_000L
            "千" -> 1_000L
            else -> 1L
        }
        return (num * mult).toLong()
    }

    /** 解析更新时间，尽力而为。 */
    private fun parseTime(raw: String?): Long {
        val s = raw?.trim() ?: return 0
        if (s.isEmpty()) return 0
        // 纯日期 2026-11-26 / 2026/11/26
        Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})""").find(s)?.let {
            val y = it.groupValues[1].toIntOrNull() ?: return 0
            val mo = it.groupValues[2].toIntOrNull() ?: return 0
            val d = it.groupValues[3].toIntOrNull() ?: return 0
            return runCatching {
                java.time.LocalDate.of(y, mo, d).toEpochDay()
            }.getOrDefault(0L)
        }
        // 相对时间
        if (s.contains("分钟") || s.contains("小时") || s.contains("刚刚")) return Long.MAX_VALUE / 2
        val days = Regex("""(\d+)\s*天前""").find(s)
        if (days != null) {
            val n = days.groupValues[1].toLongOrNull() ?: 0
            return java.time.LocalDate.now().toEpochDay() - n
        }
        return 0
    }

    /** 可用的主分类列表（R18 关闭时不含成人向）。 */
    fun availableGenres(adultOn: Boolean): List<BookClassifier.Genre> =
        BookClassifier.Genre.entries.filter { adultOn || it != BookClassifier.Genre.ADULT }

    companion object {
        /** 单源最多翻几页：防止用户一直滚把站爬穿。 */
        const val MAX_PAGES = 6

        /**
         * 没有发现页的源用这些词兜底搜索。
         *
         * 挑的是覆盖最广的通用词 —— 目标不是"搜到好书"，
         * 而是"让这个源至少有内容出现在书城里"。
         */
        val FALLBACK_KEYWORDS = listOf("神", "仙", "都市", "系统", "重生", "末世")
    }
}
