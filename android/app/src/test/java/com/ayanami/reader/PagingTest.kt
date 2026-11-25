package com.ayanami.reader

import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.TocRule
import com.ayanami.reader.core.model.ContentRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking

/**
 * 分页逻辑的端到端测试 —— 起一个本地 HTTP 服务器模拟分页站点。
 *
 * 为什么不真连外网：分页行为要可控（第几页返回什么、什么时候停），
 * 真站点没法构造"下一页 404"这种边界。
 */
class PagingTest {

    private fun serve(routes: Map<String, String>): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.toString()
            val body = routes[path]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
        return server to server.address.port
    }

    private fun source(base: String, toc: TocRule, content: ContentRule) = BookSource(
        bookSourceUrl = base,
        bookSourceName = "本地测试源",
        bookSourceEnabled = true,
        searchUrl = base + "/search?k={{key}}",
        ruleSearch = com.ayanami.reader.core.model.SearchRule(
            bookList = "li.b",
            name = "a@text",
            bookUrl = "a@href"
        ),
        ruleBookInfo = com.ayanami.reader.core.model.BookInfoRule(
            name = "h1@text",
            tocUrl = ""
        ),
        ruleToc = toc,
        ruleContent = content
    )

    // ---------------- 目录分页 ----------------

    @Test
    fun `toc follows nextTocUrl across pages`() = runBlocking {
        val routes = mapOf(
            "/toc/1" to """<ul><li><a href="/c/1">第1章</a></li><li><a href="/c/2">第2章</a></li></ul>
                           <a class="next" href="/toc/2">下一页</a>""",
            "/toc/2" to """<ul><li><a href="/c/3">第3章</a></li></ul>
                           <a class="next" href="/toc/3">下一页</a>""",
            "/toc/3" to """<ul><li><a href="/c/4">第4章</a></li></ul>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(
                    chapterList = "ul > li",
                    chapterName = "a@text",
                    chapterUrl = "a@href",
                    nextTocUrl = "a.next@href"
                ),
                ContentRule(content = "div#txt@html")
            )
            val session = com.ayanami.reader.core.pipeline.SourceSession(src)
            val toc = session.toc(Book(tocUrl = "$base/toc/1", bookUrl = "$base/book"))
            assertEquals("三页应合并成 4 章", 4, toc.size)
            assertEquals("第1章", toc[0].title)
            assertEquals("第4章", toc[3].title)
            assertTrue("地址应是绝对地址：${toc[3].url}", toc[3].url.startsWith("http"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `toc stops on 404 instead of looping`() = runBlocking {
        val routes = mapOf(
            "/toc/1" to """<ul><li><a href="/c/1">第1章</a></li></ul>
                           <a class="next" href="/toc/missing">下一页</a>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(
                    chapterList = "ul > li",
                    chapterName = "a@text",
                    chapterUrl = "a@href",
                    nextTocUrl = "a.next@href"
                ),
                ContentRule(content = "div#txt@html")
            )
            val toc = com.ayanami.reader.core.pipeline.SourceSession(src)
                .toc(Book(tocUrl = "$base/toc/1", bookUrl = "$base/book"))
            assertEquals("第一页的章要保留", 1, toc.size)
        } finally { srv.stop(0) }
    }

    @Test
    fun `toc without next rule stays single page`() = runBlocking {
        val routes = mapOf(
            "/toc/1" to """<ul><li><a href="/c/1">第1章</a></li><li><a href="/c/2">第2章</a></li></ul>
                           <a class="next" href="/toc/2">下一页</a>""",
            "/toc/2" to """<ul><li><a href="/c/9">不该出现</a></li></ul>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(chapterList = "ul > li", chapterName = "a@text", chapterUrl = "a@href"),
                ContentRule(content = "div#txt@html")
            )
            val toc = com.ayanami.reader.core.pipeline.SourceSession(src)
                .toc(Book(tocUrl = "$base/toc/1", bookUrl = "$base/book"))
            assertEquals("没配 nextTocUrl 就不该跟进", 2, toc.size)
        } finally { srv.stop(0) }
    }

    // ---------------- 正文分页 ----------------

    @Test
    fun `content merges paginated pages`() = runBlocking {
        val p1 = "第一页的内容。" + "甲".repeat(60)
        val p2 = "第二页的内容。" + "乙".repeat(60)
        val p3 = "第三页的内容。" + "丙".repeat(60)
        val routes = mapOf(
            "/c/1" to """<div id="txt"><p>$p1</p></div><a class="next" href="/c/1_2">下一页</a>""",
            "/c/1_2" to """<div id="txt"><p>$p2</p></div><a class="next" href="/c/1_3">下一页</a>""",
            "/c/1_3" to """<div id="txt"><p>$p3</p></div>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(chapterList = "ul > li", chapterName = "a@text", chapterUrl = "a@href"),
                ContentRule(content = "div#txt@html", nextContentUrl = "a.next@href")
            )
            val session = com.ayanami.reader.core.pipeline.SourceSession(src)
            val ch = com.ayanami.reader.core.model.BookChapter(title = "第1章", url = "$base/c/1")
            val text = session.content(ch)
            assertTrue("应有正文", text != null)
            val t = text!!.text
            assertTrue("应包含第一页", t.contains("第一页的内容"))
            assertTrue("应包含第二页", t.contains("第二页的内容"))
            assertTrue("应包含第三页", t.contains("第三页的内容"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `content stops when next page breaks`() = runBlocking {
        val p1 = "只有第一页。" + "甲".repeat(60)
        val routes = mapOf(
            "/c/1" to """<div id="txt"><p>$p1</p></div><a class="next" href="/dead">下一页</a>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(chapterList = "ul > li", chapterName = "a@text", chapterUrl = "a@href"),
                ContentRule(content = "div#txt@html", nextContentUrl = "a.next@href")
            )
            val ch = com.ayanami.reader.core.model.BookChapter(title = "第1章", url = "$base/c/1")
            val text = com.ayanami.reader.core.pipeline.SourceSession(src).content(ch)
            assertTrue("第二页 404 时不能整体失败", text != null)
            assertTrue("应保留第一页", text!!.text.contains("只有第一页"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `content loop guard stops infinite next links`() = runBlocking {
        val p = "相同内容。" + "甲".repeat(60)
        // 永远指向自己 —— 没有 visited 集合就会无限抓
        val routes = mapOf(
            "/c/1" to """<div id="txt"><p>$p</p></div><a class="next" href="/c/1">下一页</a>"""
        )
        val (srv, port) = serve(routes)
        try {
            val base = "http://127.0.0.1:$port"
            val src = source(
                base,
                TocRule(chapterList = "ul > li", chapterName = "a@text", chapterUrl = "a@href"),
                ContentRule(content = "div#txt@html", nextContentUrl = "a.next@href")
            )
            val start = System.currentTimeMillis()
            val ch = com.ayanami.reader.core.model.BookChapter(title = "第1章", url = "$base/c/1")
            val text = com.ayanami.reader.core.pipeline.SourceSession(src).content(ch)
            val cost = System.currentTimeMillis() - start
            assertTrue("应有内容", text != null)
            assertEquals("同一页只能抓一次", 1, Regex("相同内容").findAll(text!!.text).count())
            assertTrue("不该无限循环，实际 ${cost}ms", cost < 10_000)
        } finally { srv.stop(0) }
    }
}
