package com.ayanami.reader

import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.bookstore.BookstoreSnapshot
import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.net.HostGate
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.ui.reader.ChapterPaginator
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.6.1 新增引擎的契约测试。
 *
 * 只测**纯逻辑**，不碰 Android 框架（这样能在 JVM 上秒跑）。
 * Compose 的分页需要 `TextMeasurer`，那部分在真机/模拟器上验证，
 * 这里只锁住不依赖渲染的数学部分。
 */
class Phase1EngineTest {

    // ---------------- 并发闸门 ----------------

    @Test
    fun `hostOf extracts host and lowercases`() {
        assertEquals("a.com", HostGate.hostOf("https://A.com/x/y"))
        assertEquals("b.cn", HostGate.hostOf("http://b.cn:8080/p?q=1"))
    }

    @Test
    fun `hostOf falls back to raw string when unparsable`() {
        // 宁可串行，也不要因为解析失败就无限制并发
        assertEquals("not a url", HostGate.hostOf("not a url"))
    }

    @Test
    fun `same host runs serially`() = runBlocking {
        HostGate.reset()
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val jobs = (1..6).map {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                HostGate.run("https://same-host.test/a", intervalMs = 0) {
                    val now = concurrent.incrementAndGet()
                    if (now > maxSeen.get()) maxSeen.set(now)
                    Thread.sleep(20)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals("同域名必须串行，峰值并发应为 1", 1, maxSeen.get())
    }

    @Test
    fun `different hosts run in parallel`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(4)
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val jobs = (1..4).map { i ->
            launch(kotlinx.coroutines.Dispatchers.IO) {
                HostGate.run("https://host$i.test/", intervalMs = 0) {
                    val now = concurrent.incrementAndGet()
                    if (now > maxSeen.get()) maxSeen.set(now)
                    Thread.sleep(60)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("跨域名应能并行，实测峰值=${maxSeen.get()}", maxSeen.get() >= 2)
    }

    @Test
    fun `global concurrency cap holds`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(2)
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val jobs = (1..8).map { i ->
            launch(kotlinx.coroutines.Dispatchers.IO) {
                HostGate.run("https://cap$i.test/", intervalMs = 0) {
                    val now = concurrent.incrementAndGet()
                    if (now > maxSeen.get()) maxSeen.set(now)
                    Thread.sleep(30)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("全局并发不得超过 2，实测=${maxSeen.get()}", maxSeen.get() <= 2)
        HostGate.reconfigure(4)
    }

    @Test
    fun `pace does not serialize different requests on same host`() = runBlocking {
        // pace 只做间隔不管并发：下载引擎自己按 host 限 2，不能再被压成 1
        HostGate.reset()
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val jobs = (1..4).map {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                HostGate.pace("https://dl.test/x", intervalMs = 0) {
                    val now = concurrent.incrementAndGet()
                    if (now > maxSeen.get()) maxSeen.set(now)
                    Thread.sleep(40)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("pace 不应把并发压成 1，实测=${maxSeen.get()}", maxSeen.get() >= 2)
    }

    @Test
    fun `interval is actually enforced between same host calls`() = runBlocking {
        HostGate.reset()
        val t0 = System.currentTimeMillis()
        HostGate.pace("https://slow.test/a", intervalMs = 150) { }
        HostGate.pace("https://slow.test/a", intervalMs = 150) { }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("两次同 host 请求应至少间隔 150ms，实测 ${elapsed}ms", elapsed >= 140)
    }

    // ---------------- 体检状态语义 ----------------

    @Test
    fun `hard failure statuses skip search`() {
        assertTrue(SourceChecker.UiStatus.CONNECT_FAILED.isHardFailure)
        assertTrue(SourceChecker.UiStatus.NEEDS_LOGIN.isHardFailure)
        assertTrue(SourceChecker.UiStatus.UNSUPPORTED.isHardFailure)
    }

    @Test
    fun `transient statuses still get searched`() {
        // 没取到正文换个关键词可能就有了；没体检过也不等于坏
        assertFalse(SourceChecker.UiStatus.NO_CONTENT.isHardFailure)
        assertFalse(SourceChecker.UiStatus.UNVERIFIED.isHardFailure)
        assertFalse(SourceChecker.UiStatus.READABLE.isHardFailure)
    }

    // ---------------- 书城元数据合并 ----------------

    private fun sb(
        name: String,
        author: String = "作者",
        origin: String = "https://a.com",
        cover: String? = null,
        intro: String? = null,
        wordCount: String? = null
    ) = SearchBook(
        name = name, author = author, origin = origin, originName = origin,
        coverUrl = cover, intro = intro, wordCount = wordCount, bookUrl = "$origin/$name"
    )

    private fun store(book: SearchBook) = BookstoreEngine.StoreBook(
        book = book,
        classification = BookClassifier.classify(name = book.name, author = book.author)
    )

    @Test
    fun `merged book fills missing metadata from later source`() {
        val engine = BookstoreEngine(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val first = store(sb("书名", origin = "https://a.com"))          // 什么元数据都没有
        val second = store(
            sb(
                "书名", origin = "https://b.com",
                cover = "https://b.com/c.jpg", intro = "有简介", wordCount = "12万字"
            )
        )

        val merged = with(engine) { first.mergedWith(second) }

        assertEquals("书名保持不变", "书名", merged.book.name)
        assertEquals("主源仍是首条", "https://a.com", merged.book.origin)
        assertEquals("封面必须从次条补齐", "https://b.com/c.jpg", merged.book.coverUrl)
        assertEquals("简介必须补齐", "有简介", merged.book.intro)
        assertEquals("字数必须补齐", "12万字", merged.book.wordCount)
        assertEquals("可切换源应含 b.com", 1, merged.switchableOrigins.size)
        assertEquals("https://b.com", merged.switchableOrigins.first().origin)
    }

    @Test
    fun `merge never overwrites existing values`() {
        val engine = BookstoreEngine(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val first = store(sb("书名", origin = "https://a.com", cover = "https://a.com/a.jpg", intro = "原简介"))
        val second = store(sb("书名", origin = "https://b.com", cover = "https://b.com/b.jpg", intro = "次简介"))

        val merged = with(engine) { first.mergedWith(second) }

        assertEquals("已有的封面不该被覆盖", "https://a.com/a.jpg", merged.book.coverUrl)
        assertEquals("已有的简介不该被覆盖", "原简介", merged.book.intro)
    }

    @Test
    fun `merge accumulates origins without duplicates`() {
        val engine = BookstoreEngine(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        var acc = store(sb("书", origin = "https://a.com"))
        acc = with(engine) { acc.mergedWith(store(sb("书", origin = "https://b.com"))) }
        acc = with(engine) { acc.mergedWith(store(sb("书", origin = "https://c.com"))) }
        acc = with(engine) { acc.mergedWith(store(sb("书", origin = "https://b.com"))) }

        // 命中过 a/b/c 三个源，但 a 是当前主源，可切换的只有 b 和 c。
        // 重复的 b 必须被去重掉。
        assertEquals("可切换源应为 b、c 两个（a 是主源自己的）", 2, acc.switchableOrigins.size)
        assertEquals(
            listOf("https://b.com", "https://c.com"),
            acc.switchableOrigins.map { it.origin }.sorted()
        )
    }

    @Test
    fun `switchableOrigins excludes self and dedupes`() {
        val self = sb("书", origin = "https://a.com")
        val other = sb("书", origin = "https://b.com")
        val otherDup = sb("书", origin = "https://b.com")
        val s = BookstoreEngine.StoreBook(
            book = self,
            classification = BookClassifier.classify(name = "书"),
            altOrigins = listOf(other, otherDup)
        )
        assertEquals("应去重成 1 个可切换源", 1, s.switchableOrigins.size)
        assertEquals("https://b.com", s.switchableOrigins.first().origin)

        // 自己不该出现在可切换列表里
        val withSelf = s.copy(altOrigins = listOf(self, other))
        assertEquals(1, withSelf.switchableOrigins.size)
    }

    @Test
    fun `storeBook id is origin plus url`() {
        val s = store(sb("书"))
        assertEquals("https://a.com\u0000https://a.com/书", s.id)
    }

    // ---------------- 书城快照 ----------------

    @Test
    fun `snapshot round trips books`() {
        val dir = TemporaryFolder().apply { create() }.root
        val books = listOf(
            store(sb("甲", author = "A", cover = "https://x/c.jpg")),
            store(sb("乙", author = "B", origin = "https://b.com"))
        )
        BookstoreSnapshot.save(dir, books)

        val loaded = BookstoreSnapshot.load(dir)
        assertNotNull("快照应能读回", loaded)
        assertEquals(2, loaded!!.books.size)
        assertEquals("甲", loaded.books[0].book.name)
        assertEquals("https://x/c.jpg", loaded.books[0].book.coverUrl)
        assertEquals("乙", loaded.books[1].book.name)
    }

    @Test
    fun `snapshot recomputes classification instead of persisting it`() {
        val dir = TemporaryFolder().apply { create() }.root
        // 用带成人关键词的书，验证读回来时分类被重新算过
        BookstoreSnapshot.save(dir, listOf(store(sb("母子上位"))))
        val loaded = BookstoreSnapshot.load(dir)!!
        assertTrue(
            "快照读回时必须重算分类，成人书应仍被标为 adult",
            loaded.books[0].classification.isAdult
        )
    }

    @Test
    fun `snapshot returns null when missing or empty`() {
        val dir = TemporaryFolder().apply { create() }.root
        assertNull("没有文件时应返回 null", BookstoreSnapshot.load(dir))
        BookstoreSnapshot.save(dir, emptyList())
        assertNull("空列表不应产出快照", BookstoreSnapshot.load(dir))
    }

    @Test
    fun `stale snapshot is rejected when allowStale false`() {
        val dir = TemporaryFolder().apply { create() }.root
        BookstoreSnapshot.save(dir, listOf(store(sb("书"))))
        // 把保存时间改成 2 小时前
        val f = BookstoreSnapshot.file(dir)
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        f.writeText(f.readText().replaceFirst(Regex("\"savedAt\":\\d+"), "\"savedAt\":$old"))

        assertNull(
            "过期快照在 allowStale=false 时应被拒绝",
            BookstoreSnapshot.load(dir, allowStale = false)
        )
        assertNotNull(
            "allowStale=true 时过期快照仍可用",
            BookstoreSnapshot.load(dir, allowStale = true)
        )
    }

    @Test
    fun `fresh snapshot is accepted when allowStale false`() {
        val dir = TemporaryFolder().apply { create() }.root
        BookstoreSnapshot.save(dir, listOf(store(sb("书"))))
        assertNotNull(BookstoreSnapshot.load(dir, allowStale = false))
    }

    @Test
    fun `clear removes snapshot`() {
        val dir = TemporaryFolder().apply { create() }.root
        BookstoreSnapshot.save(dir, listOf(store(sb("书"))))
        assertTrue(BookstoreSnapshot.file(dir).exists())
        BookstoreSnapshot.clear(dir)
        assertFalse(BookstoreSnapshot.file(dir).exists())
    }

    // ---------------- 分页（不依赖渲染的数学部分） ----------------

    @Test
    fun `pageOfChar finds containing page`() {
        val pages = listOf(
            ChapterPaginator.Page(0, 100),
            ChapterPaginator.Page(100, 250),
            ChapterPaginator.Page(250, 400)
        )
        assertEquals(0, ChapterPaginator.pageOfChar(pages, 0))
        assertEquals(0, ChapterPaginator.pageOfChar(pages, 99))
        assertEquals(1, ChapterPaginator.pageOfChar(pages, 100))
        assertEquals(1, ChapterPaginator.pageOfChar(pages, 249))
        assertEquals(2, ChapterPaginator.pageOfChar(pages, 399))
    }

    @Test
    fun `pageOfChar clamps beyond end`() {
        val pages = listOf(ChapterPaginator.Page(0, 50), ChapterPaginator.Page(50, 100))
        assertEquals(1, ChapterPaginator.pageOfChar(pages, 100))
        assertEquals(1, ChapterPaginator.pageOfChar(pages, 9999))
        assertEquals(0, ChapterPaginator.pageOfChar(emptyList(), 5))
    }

    @Test
    fun `textOfPage slices correctly and clamps`() {
        val text = "0123456789"
        assertEquals("0123", ChapterPaginator.textOfPage(text, ChapterPaginator.Page(0, 4)))
        assertEquals("456789", ChapterPaginator.textOfPage(text, ChapterPaginator.Page(4, 10)))
        // 越界要收敛，不能抛异常
        assertEquals("789", ChapterPaginator.textOfPage(text, ChapterPaginator.Page(7, 999)))
        assertEquals("", ChapterPaginator.textOfPage(text, ChapterPaginator.Page(999, 1000)))
    }

    @Test
    fun `pages cover the whole text without gaps when contiguous`() {
        val text = "a".repeat(1000)
        val pages = listOf(
            ChapterPaginator.Page(0, 300),
            ChapterPaginator.Page(300, 600),
            ChapterPaginator.Page(600, 1000)
        )
        val rebuilt = pages.joinToString("") { ChapterPaginator.textOfPage(text, it) }
        assertEquals("分页不能丢字", text, rebuilt)
        // 相邻页首尾相接
        for (i in 0 until pages.size - 1) {
            assertEquals(pages[i].end, pages[i + 1].start)
        }
    }
}
