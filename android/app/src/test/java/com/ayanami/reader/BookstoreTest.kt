package com.ayanami.reader

import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.ExploreRule
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.model.SearchRule
import com.ayanami.reader.core.pipeline.SourceChecker
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 书城聚合引擎的契约测试。
 *
 * 重点防三件事：
 * 1. **未通过体检的源不能进书城** —— 否则用户点进去就是空的
 * 2. **R18 未开启时成人书绝不泄漏** —— 最严重的产品事故
 * 3. **字数/时间解析不能崩** —— 书源给的格式全是野的（"123万"、"连载中"）
 */
class BookstoreTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val routes = HashMap<String, String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.toString()
            val body = routes[path] ?: routes.entries.firstOrNull {
                path.startsWith(it.key)
            }?.value
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private val base: String get() = "http://127.0.0.1:$port"

    /**
     * @param pathSuffix 给 bookSourceUrl 加不同后缀 —— 同 URL 的两个源会互相覆盖
     *   checks 表里的条目，测不出「按源筛选」的语义
     */
    private fun source(name: String, exploreUrl: String? = null, pathSuffix: String = "") = BookSource(
        bookSourceUrl = base + pathSuffix,
        bookSourceName = name,
        exploreUrl = exploreUrl,
        searchUrl = "/s?q={{key}}",
        ruleSearch = SearchRule(bookList = "class.odd", name = "tag.a@text", bookUrl = "tag.a@href"),
        ruleExplore = ExploreRule(
            bookList = "class.odd",
            name = "tag.a@text",
            bookUrl = "tag.a@href",
            author = "class.au@text",
            intro = "class.in@text",
            wordCount = "class.wc@text",
            updateTime = "class.ut@text"
        )
    )

    private fun readable(url: String) = SourceChecker.CheckResult(
        sourceUrl = url,
        status = SourceChecker.UiStatus.READABLE,
        elapsedMs = 100,
        stuckAt = "done"
    )

    private fun notReadable(url: String) = SourceChecker.CheckResult(
        sourceUrl = url,
        status = SourceChecker.UiStatus.NO_CONTENT,
        elapsedMs = 100,
        stuckAt = "toc"
    )

    // ---------------- 源筛选 ----------------

    @Test
    fun `only readable sources are usable`() {
        val s1 = source("好源", pathSuffix = "/a")
        val s2 = source("坏源", pathSuffix = "/b")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(
            sources = listOf(s1, s2),
            checks = mapOf(
                s1.bookSourceUrl to readable(s1.bookSourceUrl),
                s2.bookSourceUrl to notReadable(s2.bookSourceUrl)
            )
        )
        assertEquals("只有通过体检的源才能进书城", 1, usable.size)
        assertEquals("好源", usable[0].displayName)
    }

    @Test
    fun `disabled source is excluded even if readable`() {
        val s = source("禁用的源").copy(bookSourceEnabled = false)
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(
            listOf(s), mapOf(s.bookSourceUrl to readable(s.bookSourceUrl))
        )
        assertTrue(usable.isEmpty())
    }

    @Test
    fun `source without check result is excluded`() {
        val s = source("未体检")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        assertTrue(engine.usableSources(listOf(s), emptyMap()).isEmpty())
    }

    @Test
    fun `source without explore needs search url`() {
        val withSearch = source("有搜索")
        val noSearch = source("无搜索").copy(searchUrl = null)
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(
            listOf(withSearch, noSearch),
            mapOf(
                withSearch.bookSourceUrl to readable(withSearch.bookSourceUrl),
                noSearch.bookSourceUrl to readable(noSearch.bookSourceUrl)
            )
        )
        assertEquals("没有发现页也没搜索的源不能逛", 1, usable.size)
        assertEquals("有搜索", usable[0].displayName)
    }

    // ---------------- 抓取与分类 ----------------

    @Test
    fun `loads page and classifies books`() = runBlocking {
        routes["/list"] = """
            <ul>
              <li class="odd"><a href="/b/1">星际机甲</a>
                <span class="au">张三</span>
                <span class="in">舰队穿越星系，人工智能掌控殖民地。</span>
                <span class="wc">120万</span>
                <span class="ut">2026-11-20</span></li>
              <li class="odd"><a href="/b/2">仙逆</a>
                <span class="au">李四</span>
                <span class="in">踏入修真界，历经炼气金丹元婴，渡劫飞升。</span>
                <span class="wc">300万字</span>
                <span class="ut">2026-11-25</span></li>
            </ul>
        """.trimIndent()

        val s = source("测试源", exploreUrl = "全部::/list")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(listOf(s), mapOf(s.bookSourceUrl to readable(s.bookSourceUrl)))
        val page = engine.loadPage(usable, adultOn = false, perSource = 10, firstPage = true)

        assertEquals(2, page.books.size)
        // 分类必须本地算出来，不能信源（源根本没给 kind）
        val mecha = page.books.first { it.book.name == "星际机甲" }
        assertEquals(BookClassifier.Genre.SCIFI, mecha.classification.genre)
        val xianxia = page.books.first { it.book.name == "仙逆" }
        assertEquals(BookClassifier.Genre.WUXIA, xianxia.classification.genre)
    }

    @Test
    fun `deduplicates across sources`() = runBlocking {
        // 两个源返回同一本书（同名同作者）→ 只留一条
        routes["/list"] = """
            <ul><li class="odd"><a href="/b/1">同一本书</a>
              <span class="au">同作者</span></li></ul>
        """.trimIndent()

        val s1 = source("源A", exploreUrl = "全部::/list")
        val s2 = source("源B", exploreUrl = "全部::/list")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(
            listOf(s1, s2),
            mapOf(
                s1.bookSourceUrl to readable(s1.bookSourceUrl),
                s2.bookSourceUrl to readable(s2.bookSourceUrl)
            )
        )
        val page = engine.loadPage(usable, adultOn = false, perSource = 10, firstPage = true)
        assertEquals("同名同作者应跨源去重", 1, page.books.size)
    }

    // ---------------- R18 硬过滤（最关键） ----------------

    @Test
    fun `adult books are filtered when switch is off`() = runBlocking {
        routes["/list"] = """
            <ul>
              <li class="odd"><a href="/b/1">我的母亲</a>
                <span class="in">母亲一个人把我拉扯大……</span></li>
              <li class="odd"><a href="/b/2">星舰指挥官</a>
                <span class="in">舰队穿越星系。</span></li>
            </ul>
        """.trimIndent()

        val s = source("测试源", exploreUrl = "全部::/list")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(listOf(s), mapOf(s.bookSourceUrl to readable(s.bookSourceUrl)))

        val off = engine.loadPage(usable, adultOn = false, perSource = 10, firstPage = true)
        assertEquals("关掉 R18 时成人书必须被丢弃", 1, off.books.size)
        assertEquals("星舰指挥官", off.books[0].book.name)
        assertTrue("剩下的不能是成人书", !off.books[0].classification.isAdult)

        // 重新开一次（reset 后），开 R18 应能拿到两本
        val on = engine.loadPage(usable, adultOn = true, perSource = 10, firstPage = true)
        assertEquals("开 R18 应拿到两本", 2, on.books.size)
        assertTrue("应包含成人书", on.books.any { it.classification.isAdult })
    }

    // ---------------- 筛选与排序 ----------------

    @Test
    fun `filter by genre rating and source`() {
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val books = listOf(
            storeBook("科幻书", BookClassifier.Genre.SCIFI, isAdult = false, origin = "a"),
            storeBook("仙侠书", BookClassifier.Genre.WUXIA, isAdult = false, origin = "b"),
            storeBook("成人书", BookClassifier.Genre.ADULT, isAdult = true, origin = "a")
        )

        assertEquals(3, engine.filterByGenre(books, "ALL").size)
        assertEquals(1, engine.filterByGenre(books, "SCIFI").size)
        assertEquals(2, engine.filterBySource(books, "a").size)
        assertEquals(2, engine.filterByRating(books, "GENERAL").size)
        assertEquals(1, engine.filterByRating(books, "ADULT").size)
    }

    @Test
    fun `word count parsing handles messy formats`() {
        val e = BookstoreEngine(CoroutineScopeStub.scope)
        assertEquals(1234567L, e.parseWordCount("1234567"))
        assertEquals(1_200_000L, e.parseWordCount("120万"))
        assertEquals(3_000_000L, e.parseWordCount("300万字"))
        assertEquals(12_000L, e.parseWordCount("12千字"))
        assertEquals(100_000_000L, e.parseWordCount("1亿"))
        assertEquals("非数字应返回 -1", -1L, e.parseWordCount("连载中"))
        assertEquals("null 应返回 -1", -1L, e.parseWordCount(null))
        assertEquals("空串应返回 -1", -1L, e.parseWordCount(""))
    }

    @Test
    fun `sort by word count desc and asc`() {
        val e = BookstoreEngine(CoroutineScopeStub.scope)
        val books = listOf(
            storeBook("少", BookClassifier.Genre.OTHER, wordCount = "10万"),
            storeBook("多", BookClassifier.Genre.OTHER, wordCount = "500万"),
            storeBook("中", BookClassifier.Genre.OTHER, wordCount = "100万")
        )
        val desc = e.sort(books, "WORD_COUNT_DESC")
        assertEquals(listOf("多", "中", "少"), desc.map { it.book.name })

        val asc = e.sort(books, "WORD_COUNT_ASC")
        assertEquals(listOf("少", "中", "多"), asc.map { it.book.name })
    }

    @Test
    fun `sort by recent uses update time`() {
        val e = BookstoreEngine(CoroutineScopeStub.scope)
        val books = listOf(
            storeBook("旧", BookClassifier.Genre.OTHER, updateTime = "2026-01-01"),
            storeBook("新", BookClassifier.Genre.OTHER, updateTime = "2026-11-25")
        )
        assertEquals("新", e.sort(books, "RECENT")[0].book.name)
    }

    @Test
    fun `unknown sort mode keeps original order`() {
        val e = BookstoreEngine(CoroutineScopeStub.scope)
        val books = listOf(
            storeBook("A", BookClassifier.Genre.OTHER),
            storeBook("B", BookClassifier.Genre.OTHER)
        )
        assertEquals(listOf("A", "B"), e.sort(books, "WHATEVER").map { it.book.name })
    }

    @Test
    fun `available genres excludes adult when off`() {
        val e = BookstoreEngine(CoroutineScopeStub.scope)
        assertTrue(!e.availableGenres(false).contains(BookClassifier.Genre.ADULT))
        assertTrue(e.availableGenres(true).contains(BookClassifier.Genre.ADULT))
    }

    // ---------------- 分页 ----------------

    @Test
    fun `has more false after reaching max pages`() = runBlocking {
        routes["/list"] = """
            <ul><li class="odd"><a href="/b/1">书</a></li></ul>
        """.trimIndent()

        val s = source("测试源", exploreUrl = "全部::/list")
        val engine = BookstoreEngine(CoroutineScopeStub.scope)
        val usable = engine.usableSources(listOf(s), mapOf(s.bookSourceUrl to readable(s.bookSourceUrl)))

        var page = engine.loadPage(usable, adultOn = false, perSource = 5, firstPage = true)
        var guard = 0
        while (page.hasMore && guard++ < 20) {
            page = engine.loadPage(usable, adultOn = false, perSource = 5, firstPage = false)
        }
        assertTrue("翻到上限后 hasMore 必须为 false", !page.hasMore)
        assertTrue("不应无限翻页", guard <= BookstoreEngine.MAX_PAGES + 2)
    }

    // ---------------- helpers ----------------

    private fun storeBook(
        name: String,
        genre: BookClassifier.Genre,
        isAdult: Boolean = false,
        origin: String = "o",
        wordCount: String? = null,
        updateTime: String? = null
    ) = BookstoreEngine.StoreBook(
        book = SearchBook(
            name = name,
            author = "",
            bookUrl = "http://x/$name",
            origin = origin,
            originName = origin,
            wordCount = wordCount,
            updateTime = updateTime
        ),
        classification = BookClassifier.Result(
            genre = genre,
            tags = emptyList(),
            isAdult = isAdult
        )
    )
}

/** 给引擎一个可用的 CoroutineScope（引擎只在调度里用，测试不依赖生命周期）。 */
private object CoroutineScopeStub {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
}
