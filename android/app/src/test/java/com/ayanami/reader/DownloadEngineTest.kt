package com.ayanami.reader

import com.ayanami.reader.core.download.DownloadEngine
import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookChapter
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.ContentRule
import com.ayanami.reader.core.model.TocRule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 离线缓存引擎的契约测试。
 *
 * 重点防四件事：
 * 1. **断点续传** —— 已缓存的章节不能被重下（用户下到一半退出很正常）
 * 2. **失败重试** —— 单章失败不能拖垮整本
 * 3. **按域名限流** —— 无节制并发会被站方判成 CC 攻击
 * 4. **缓存正确性** —— 第一行标题 + 正文，导出时能拆回来
 */
class DownloadEngineTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var cacheDir: File
    private val requestCount = AtomicInteger(0)
    private val failedOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "dl-test-${System.nanoTime()}")
        cacheDir.mkdirs()
        requestCount.set(0)
        failedOnce.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.toString()
            requestCount.incrementAndGet()
            // /c/N 返回第 N 章正文；前两次请求 /c/2 故意 500（测重试）
            if (path.startsWith("/c/")) {
                val n = path.removePrefix("/c/").toIntOrNull() ?: 0
                if (n == 2 && failedOnce.add(path) && failedOnce.size <= 1) {
                    ex.sendResponseHeaders(500, -1)
                    ex.close()
                    return@createContext
                }
                val body = """<div id="txt">第${n}章正文内容，${"这里是一些测试文字。".repeat(20)}</div>"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        cacheDir.deleteRecursively()
    }

    private val base: String get() = "http://127.0.0.1:$port"

    private fun source() = BookSource(
        bookSourceUrl = base,
        bookSourceName = "本地源",
        ruleToc = TocRule(chapterList = "li", chapterName = "a@text", chapterUrl = "a@href"),
        ruleContent = ContentRule(content = "id.txt@text")
    )

    private fun book() = Book(name = "测试书", author = "作者", bookUrl = "$base/b/1")

    private fun toc(n: Int) = (0 until n).map {
        BookChapter(title = "第${it + 1}章", url = "$base/c/$it", index = it)
    }

    private fun engine() = DownloadEngine(CoroutineScope(Dispatchers.Default), cacheDir)

    // ---------------- 基础 ----------------

    @Test
    fun `writes and reads cache correctly`() {
        val e = engine()
        assertTrue(!e.isCached("u", 0))
        e.writeCached("u", 0, "第一章", "正文内容")
        assertTrue(e.isCached("u", 0))

        val raw = e.readCached("u", 0)
        assertNotNull(raw)
        assertEquals("第一章", raw!!.substringBefore('\n'))
        assertEquals("正文内容", raw.substringAfter('\n'))
        assertEquals(1, e.cachedCount("u", 5))
    }

    @Test
    fun `book key is stable and filesystem safe`() {
        val e = engine()
        val k1 = e.bookKey("https://a.com/book/1.html")
        val k2 = e.bookKey("https://a.com/book/1.html")
        assertEquals("同一个 URL 的 key 必须稳定", k1, k2)
        assertTrue("key 不能含路径分隔符：$k1", !k1.contains('/') && !k1.contains('\\'))
        assertTrue("key 不能含冒号：$k1", !k1.contains(':'))

        // 不同 URL 必须不同
        assertTrue(k1 != e.bookKey("https://a.com/book/2.html"))
    }

    @Test
    fun `clear book removes all chapters`() {
        val e = engine()
        e.writeCached("u", 0, "一", "正文1")
        e.writeCached("u", 1, "二", "正文2")
        assertTrue(e.clearBook("u"))
        assertTrue(!e.isCached("u", 0))
        assertTrue(!e.isCached("u", 1))
    }

    // ---------------- 断点续传（核心） ----------------

    @Test
    fun `already cached chapters are skipped`() = runBlocking {
        val e = engine()
        val b = book()
        val chapters = toc(4)

        // 手动缓存第 0、1 章，模拟"上次下到一半退出"
        e.writeCached(b.bookUrl, 0, chapters[0].title, "已有正文0")
        e.writeCached(b.bookUrl, 1, chapters[1].title, "已有正文1")

        val before = requestCount.get()
        val task = e.start(source(), b, chapters)
        withTimeout(30_000) {
            while (task.state == DownloadEngine.Task.State.RUNNING) delay(50)
        }

        assertEquals(DownloadEngine.Task.State.DONE, task.state)
        assertEquals("只该下载剩下的 2 章", 2, task.total)
        // 3 次 = 第 2 章（第一次 500）+ 第 2 章重试 + 第 3 章
        // 关键点是**不含已缓存的第 0/1 章**：若断点续传失效，这里会是 5 次
        assertEquals(
            "已缓存的章节不能重下（总请求应为 3：2 章 + 1 次重试）",
            before + 3, requestCount.get()
        )
        assertEquals(4, e.cachedCount(b.bookUrl, 4))
        assertEquals("已缓存章节的正文不能被覆盖", "已有正文0", e.readCached(b.bookUrl, 0)!!.substringAfter('\n'))
    }

    @Test
    fun `all cached means no requests at all`() = runBlocking {
        val e = engine()
        val b = book()
        val chapters = toc(3)
        chapters.forEach { e.writeCached(b.bookUrl, it.index, it.title, "正文") }

        val before = requestCount.get()
        val task = e.start(source(), b, chapters)
        assertEquals("全部已缓存应立刻 DONE", DownloadEngine.Task.State.DONE, task.state)
        assertEquals(0, task.total)
        assertEquals("不该发任何请求", before, requestCount.get())
    }

    // ---------------- 失败重试 ----------------

    @Test
    fun `failed chapter is retried and recovers`() = runBlocking {
        val e = engine()
        val b = book()
        // 5 章，其中 /c/2 第一次故意 500
        val chapters = toc(5)

        val task = e.start(source(), b, chapters)
        withTimeout(60_000) {
            while (task.state == DownloadEngine.Task.State.RUNNING) delay(50)
        }

        // 500 是 HttpStatus，重试后能成功
        assertEquals("重试后应全部成功：failed=${task.failed}", DownloadEngine.Task.State.DONE, task.state)
        assertEquals(5, e.cachedCount(b.bookUrl, 5))
    }

    // ---------------- 范围下载 ----------------

    @Test
    fun `range download only fetches selected chapters`() = runBlocking {
        val e = engine()
        val b = book()
        val chapters = toc(10)

        val task = e.start(source(), b, chapters, ranges = (2..5).toList())
        withTimeout(30_000) {
            while (task.state == DownloadEngine.Task.State.RUNNING) delay(50)
        }
        assertEquals(DownloadEngine.Task.State.DONE, task.state)
        assertEquals("只下 2..5 共 4 章", 4, task.total)
        assertTrue(e.isCached(b.bookUrl, 2))
        assertTrue(e.isCached(b.bookUrl, 5))
        assertTrue("范围外不能下", !e.isCached(b.bookUrl, 6))
        assertTrue("范围外不能下", !e.isCached(b.bookUrl, 1))
    }

    @Test
    fun `maxCount limits downloads`() = runBlocking {
        val e = engine()
        val b = book()
        val chapters = toc(10)

        val task = e.start(source(), b, chapters, maxCount = 3)
        withTimeout(30_000) {
            while (task.state == DownloadEngine.Task.State.RUNNING) delay(50)
        }
        assertEquals(3, task.total)
        assertEquals(3, e.cachedCount(b.bookUrl, 10))
    }

    // ---------------- 导出 ----------------

    @Test
    fun `export txt contains cached chapters only`() {
        val e = engine()
        val b = book()
        val chapters = toc(4)
        e.writeCached(b.bookUrl, 0, chapters[0].title, "第一章正文")
        e.writeCached(b.bookUrl, 2, chapters[2].title, "第三章正文")
        // 第 1、3 章没缓存

        val out = File(cacheDir, "out.txt")
        val result = e.exportTxt(b, chapters, out)
        assertTrue("导出应成功：${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("只导出已缓存的 2 章", 2, result.getOrThrow())

        val text = out.readText()
        assertTrue("应含书名", text.contains("测试书"))
        assertTrue("应含作者", text.contains("作者"))
        assertTrue("应含第一章", text.contains("第一章正文"))
        assertTrue("应含第三章", text.contains("第三章正文"))
        assertTrue("未缓存的章节不该出现", !text.contains("第二章正文"))
    }

    @Test
    fun `export fails when nothing cached`() {
        val e = engine()
        val out = File(cacheDir, "empty.txt")
        val result = e.exportTxt(book(), toc(3), out)
        assertTrue("没有缓存应报错", result.isFailure)
        assertTrue(
            "错误信息应提示先下载：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("下载") == true
        )
    }

    // ---------------- 按域名限流 ----------------

    @Test
    fun `host concurrency gate limits parallel requests`() = runBlocking {
        // 服务器端记录"同时在处理的请求数"峰值
        val concurrent = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val slowServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        slowServer.createContext("/") { ex: HttpExchange ->
            val now = concurrent.incrementAndGet()
            maxSeen.updateAndGet { max -> maxOf(max, now) }
            Thread.sleep(120)          // 模拟慢响应
            val n = ex.requestURI.toString().removePrefix("/c/").toIntOrNull() ?: 0
            val body = """<div id="txt">第${n}章${"内容。".repeat(40)}</div>"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            concurrent.decrementAndGet()
            ex.close()
        }
        slowServer.start()
        try {
            val slowBase = "http://127.0.0.1:${slowServer.address.port}"
            val src = BookSource(
                bookSourceUrl = slowBase,
                bookSourceName = "慢源",
                ruleContent = ContentRule(content = "id.txt@text")
            )
            val b = Book("慢书", "", "$slowBase/b/1")
            val chapters = (0 until 8).map {
                BookChapter("第${it + 1}章", "$slowBase/c/$it", it)
            }
            val e = engine()
            e.hostConcurrency = 2

            val task = e.start(src, b, chapters)
            withTimeout(60_000) {
                while (task.state == DownloadEngine.Task.State.RUNNING) delay(30)
            }
            assertEquals(DownloadEngine.Task.State.DONE, task.state)
            assertTrue(
                "同域名并发不能超过 2，实测峰值=${maxSeen.get()}",
                maxSeen.get() <= 2
            )
        } finally {
            slowServer.stop(0)
        }
    }

    // ---------------- 缓存体积 ----------------

    @Test
    fun `total cache bytes is computed`() {
        val e = engine()
        e.writeCached("a", 0, "一", "正".repeat(1000))
        e.writeCached("b", 0, "一", "文".repeat(1000))
        val bytes = e.totalCacheBytes()
        assertTrue("应统计到内容：$bytes", bytes > 3000)
    }

    @Test
    fun `readCached returns null for missing`() {
        val e = engine()
        assertNull(e.readCached("nope", 99))
    }

    // ---------------- 进度可见性（防「看起来卡死」） ----------------

    @Test
    fun `progress state is observable and reaches total`() = runBlocking {
        val e = engine()
        val b = book()
        val chapters = toc(6)
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())

        val task = e.start(source(), b, chapters) { t ->
            // 每次回调都记录当前进度 —— 模拟 Compose 的重组读取
            seen.add(t.completed)
        }
        withTimeout(60_000) {
            while (task.state == DownloadEngine.Task.State.RUNNING) delay(30)
        }

        assertEquals(DownloadEngine.Task.State.DONE, task.state)
        assertEquals("最终进度应等于总数", 6, task.completed)
        // 轨迹形如 [1,2,2,3,4,5,6,6]：每章完成回调一次，末尾 DONE 再回调一次。
        // 断言要点是「单调不减」且「最终到 6」——
        // 第一个元素不会是 0，因为第一次回调发生在第 1 章之后
        assertTrue("轨迹应单调不减，实测=$seen",
            seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("最后必须到达总数", 6, seen.last())
        assertEquals(1f, task.progress, 0.001f)
    }

    @Test
    fun `failed count is exposed separately from failed list`() {
        val e = engine()
        val b = book()
        val task = e.start(source(), b, toc(3))
        // 初始状态
        assertEquals(0, task.failedCount)
        assertEquals(0, task.completed)
        assertTrue("failed 列表与 failedCount 初始一致", task.failed.size == task.failedCount)
    }

    // ---------------- 连接池不泄漏（下载多章不该耗尽 fd） ----------------

    @Test
    fun `downloading many chapters does not exhaust connections`() = runBlocking {
        // 起一个本地服务器，连续服务 40 个章节请求。
        // 若每次请求都 newBuilder().build()（克隆连接池+线程池），
        // 这里会因为 fd/线程泄漏而超时或失败。
        val many = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        many.createContext("/") { ex: HttpExchange ->
            val n = ex.requestURI.toString().removePrefix("/c/").toIntOrNull() ?: 0
            val body = """<div id="txt">第${n}章${"正文内容。".repeat(30)}</div>"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        many.start()
        try {
            val mb = "http://127.0.0.1:${many.address.port}"
            val src = BookSource(
                bookSourceUrl = mb,
                bookSourceName = "多章源",
                ruleContent = ContentRule(content = "id.txt@text")
            )
            val b = Book("多章书", "", "$mb/b/1")
            val chapters = (0 until 40).map { BookChapter("第${it + 1}章", "$mb/c/$it", it) }

            val e = engine()
            e.hostConcurrency = 4
            val task = e.start(src, b, chapters)
            withTimeout(120_000) {
                while (task.state == DownloadEngine.Task.State.RUNNING) delay(50)
            }

            assertEquals("40 章应全部下载成功", 40, task.completed)
            assertEquals(DownloadEngine.Task.State.DONE, task.state)
            assertEquals(40, e.cachedCount(b.bookUrl, 40))
        } finally {
            many.stop(0)
        }
    }
}
