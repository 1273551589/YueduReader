package com.ayanami.reader

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.BookInfoRule
import com.ayanami.reader.core.model.ContentRule
import com.ayanami.reader.core.model.SearchRule
import com.ayanami.reader.core.model.TocRule
import com.ayanami.reader.core.pipeline.SourceSession
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * `@js` 在**每个阶段**都要能拿到当前内容。
 *
 * ## 抓到的真实 bug（v0.3.3）
 *
 * `attachJs()` 里 `currentContent` 写的是 `{ null }`：
 *
 * ```kotlin
 * val bridge = HttpJsBridge(session = this, currentContent = { null }, ...)
 * ```
 *
 * 后果：搜索/详情/正文阶段里 `@js:java.getString('a@text')` **恒返回空串**，
 * 而且 `lastError` 是 null —— 静默失败，最难查的那种。
 *
 * 更糟的是搜索阶段的**条目级** `AnalyzeRule` 根本没挂 JS 运行时，
 * 于是 `@js` 规则得到 `UnsupportedApi`，整条搜索结果被跳过（`name.isEmpty() → continue`）。
 * 实测：26 条搜索结果一条都出不来。
 *
 * 只有目录阶段是对的（因为那里单独写了 `attachJsForElement`）。
 */
class JsStageContextTest {

    private fun serve(html: String): Pair<HttpServer, String> {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            val b = html.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        srv.start()
        return srv to "http://127.0.0.1:${srv.address.port}"
    }

    /** 搜索阶段的条目级 `@js` 必须能取到值。 */
    @Test(timeout = 60_000)
    fun `搜索阶段条目级 @js 能取到值`() = runBlocking {
        val (srv, base) = serve(
            """<ul>
                 <li><a href="/b/1">书名A</a><span class="a">作者X</span>
                     <p class="i">简介甲</p></li>
                 <li><a href="/b/2">书名B</a><span class="a">作者Y</span>
                     <p class="i">简介乙</p></li>
               </ul>"""
        )
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "probe", bookSourceEnabled = true,
                searchUrl = "$base/s?k={{key}}",
                ruleSearch = SearchRule(
                    bookList = "ul > li",
                    name = "@js:java.getString('a@text')",
                    author = "@js:java.getString('span.a@text')",
                    intro = "@js:java.getString('p.i@text')",
                    bookUrl = "a@href"
                )
            )
            val hits = SourceSession(src).search("x")
            assertEquals("两条都要出来（@js 失效会导致 name 为空被 continue 掉）", 2, hits.size)
            assertEquals("书名A", hits[0].name)
            assertEquals("作者X", hits[0].author)
            assertEquals("简介甲", hits[0].intro)
            assertEquals("书名B", hits[1].name)
        } finally { srv.stop(0) }
    }

    /** 详情阶段的 `@js` 也要能取到。 */
    @Test(timeout = 60_000)
    fun `详情阶段 @js 能取到值`() = runBlocking {
        val (srv, base) = serve("""<h1>标题P</h1><span class="au">作者Q</span>""")
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "probe", bookSourceEnabled = true,
                ruleBookInfo = BookInfoRule(
                    name = "@js:java.getString('h1@text')",
                    author = "@js:java.getString('span.au@text')"
                ),
                ruleContent = ContentRule(content = "div@html")
            )
            val info = SourceSession(src).bookInfo("$base/b/1")
            assertTrue("详情必须解析出来", info != null)
            assertEquals("标题P", info!!.name)
            assertEquals("作者Q", info.author)
        } finally { srv.stop(0) }
    }

    /** 正文阶段同理 —— 这是最容易漏的，因为正文规则常写 `##正则##` 而不是 `@js`。 */
    @Test(timeout = 60_000)
    fun `正文阶段 @js 能取到值`() = runBlocking {
        val body = "正文内容。" + "甲".repeat(80)
        val (srv, base) = serve("""<div id="t"><p>$body</p></div>""")
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "probe", bookSourceEnabled = true,
                ruleContent = ContentRule(
                    // 用 @js 取 html 再交给 AnalyzerRule 清洗
                    content = "@js:java.getString('div#t@html')"
                )
            )
            val ch = com.ayanami.reader.core.model.BookChapter(title = "第1章", url = "$base/c/1")
            val text = SourceSession(src).content(ch)
            assertTrue("正文必须抓出来", text != null)
            assertTrue("正文应含内容，实际=${text!!.text.take(30)}", text.text.contains("正文内容"))
        } finally { srv.stop(0) }
    }

    /** 不含 `@js` 的书源不该建 JS 引擎（性能：26 条搜索 / 421 章目录）。 */
    @Test(timeout = 60_000)
    fun `纯 CSS 规则不建 JS 引擎`() = runBlocking {
        val (srv, base) = serve("""<ul><li><a href="/b/1">纯CSS书名</a></li></ul>""")
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "probe", bookSourceEnabled = true,
                searchUrl = "$base/s?k={{key}}",
                ruleSearch = SearchRule(bookList = "ul > li", name = "a@text", bookUrl = "a@href")
            )
            val sess = SourceSession(src)
            val hits = sess.search("x")
            assertEquals(1, hits.size)
            assertEquals("纯CSS书名", hits[0].name)
            assertTrue("不该产生 JS 日志", sess.jsLogs.isEmpty())
        } finally { srv.stop(0) }
    }
}
