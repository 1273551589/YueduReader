package com.ayanami.reader

import com.ayanami.reader.core.model.BookInfoRule
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.ContentRule
import com.ayanami.reader.core.model.SearchRule
import com.ayanami.reader.core.model.TocRule
import com.ayanami.reader.core.pipeline.SourceChecker
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 体检严格化（四段校验 + 假正文识别）的契约测试。
 *
 * 这一组防的是「绿标骗人」：以前 HTTP 200 就算通过，
 * 于是登录墙、转码中的源全部显示「抽样可读」，点进去全是空的。
 */
class StrictCheckTest {

    private lateinit var server: HttpServer
    private var port = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private val base: String get() = "http://127.0.0.1:$port"

    private fun route(path: String, html: String, charset: String = "utf-8") {
        server.createContext(path) { ex: HttpExchange ->
            val bytes = html.toByteArray(charset(charset))
            ex.responseHeaders.add("Content-Type", "text/html; charset=$charset")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
    }

    private fun src() = BookSource(
        bookSourceUrl = base,
        bookSourceName = "本地源",
        searchUrl = "/s?q={{key}}",
        ruleSearch = SearchRule(bookList = "class.odd", name = "tag.a@text", bookUrl = "tag.a@href"),
        ruleBookInfo = BookInfoRule(name = "tag.h1@text", tocUrl = "class.btn@href"),
        ruleToc = TocRule(chapterList = "class.ch", chapterName = "tag.a@text", chapterUrl = "tag.a@href"),
        ruleContent = ContentRule(content = "id.txt@text")
    )

    private fun realText(n: Int = 200): String =
        "他抬起头，看着远方的天空，心里想着那些再也回不去的日子。".repeat(n / 27 + 1).take(n)

    // ---------------- 四段全通 ----------------

    @Test
    fun `all four stages must pass for readable`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<li class="ch"><a href="/c/1.html">第一章</a></li>""")
        route("/c/1.html", """<div id="txt">${realText(300)}</div>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals("四段全通应为可读，detail=${r.detail}", SourceChecker.UiStatus.READABLE, r.status)
        assertEquals("done", r.stuckAt)
        assertEquals(4, r.stages.size)
        assertTrue("四段都应通过：${r.stages}", r.allStagesPassed)
    }

    // ---------------- 目录为空 → 不算可读 ----------------

    @Test
    fun `empty toc fails not readable`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<ul><li>暂无章节</li></ul>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, r.status)
        assertEquals("toc", r.stuckAt)
        val tocStage = r.stages.first { it.name == "toc" }
        assertTrue("目录这段应判失败", !tocStage.passed)
    }

    // ---------------- 章节地址没绝对化 → 判失败 ----------------

    @Test
    fun `chapters without absolute url fail`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        // 章节用 <span> 而不是 <a>，取不到 href → 地址为空
        route("/toc/1.html", """<li class="ch"><span>第一章</span></li>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, r.status)
        assertEquals("toc", r.stuckAt)
    }

    // ---------------- 登录墙 → 明确报需登录 ----------------

    @Test
    fun `login wall is reported as needs login not no content`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<li class="ch"><a href="/c/1.html">第一章</a></li>""")
        route(
            "/c/1.html",
            """<div id="txt">请先登录后阅读本章内容。本站所有章节均需登录访问，感谢配合。</div>"""
        )

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals(
            "登录墙应报「需登录或人机验证」而不是「未取到正文」",
            SourceChecker.UiStatus.NEEDS_LOGIN, r.status
        )
        assertEquals("content", r.stuckAt)
        assertNotNull(r.error)
    }

    @Test
    fun `vip paywall is also a login wall`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<li class="ch"><a href="/c/1.html">第一章</a></li>""")
        route("/c/1.html", """<div id="txt">本章节为VIP付费章节，请订阅后阅读。感谢支持正版。</div>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals(SourceChecker.UiStatus.NEEDS_LOGIN, r.status)
    }

    @Test
    fun `transcoding placeholder is no content`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<li class="ch"><a href="/c/1.html">第一章</a></li>""")
        route("/c/1.html", """<div id="txt">正文转码中，请稍后刷新页面重试，如有疑问请联系客服。</div>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        // 转码中不是登录墙 → 未取到正文
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, r.status)
    }

    // ---------------- 正文过短 ----------------

    @Test
    fun `too short content fails`() = runBlocking {
        route("/s", """<li class="odd"><a href="/b/1.html">测试书</a></li>""")
        route("/b/1.html", """<h1>测试书</h1><a class="btn" href="/toc/1.html">目录</a>""")
        route("/toc/1.html", """<li class="ch"><a href="/c/1.html">第一章</a></li>""")
        route("/c/1.html", """<div id="txt">太短了</div>""")

        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, r.status)
        assertEquals("content", r.stuckAt)
    }

    // ---------------- 纯函数层 ----------------

    @Test
    fun `detect fake content patterns`() {
        // 长正文里的"请先登录"是台词，不误判（只查开头 60 字）
        assertNull(
            "真正文里靠后的台词不应误判",
            SourceChecker.detectFakeContent(
                realText(500) + "他冷笑着念出那行字：请先登录后阅读。" + realText(200)
            )
        )

        val wall = SourceChecker.detectFakeContent("请先登录后阅读")
        assertNotNull(wall)
        assertTrue("应识别为登录墙", wall!!.loginWall)

        val pay = SourceChecker.detectFakeContent("本章节为VIP付费章节，请订阅后阅读")
        assertNotNull(pay)
        assertTrue(pay!!.loginWall)

        val trans = SourceChecker.detectFakeContent("正文转码中，请稍后")
        assertNotNull(trans)
        assertTrue("转码中不算登录墙", !trans!!.loginWall)

        val verify = SourceChecker.detectFakeContent("请完成人机验证后继续")
        assertNotNull(verify)
        assertTrue(verify!!.loginWall)
    }

    @Test
    fun `isReadable rejects garbage`() {
        assertTrue(SourceChecker.isReadable(realText(300)))
        assertTrue("过短应拒", !SourceChecker.isReadable("短"))
        assertTrue(
            "英文为主应拒",
            !SourceChecker.isReadable("This is English only content. ".repeat(20))
        )
        assertTrue("登录墙应拒", !SourceChecker.isReadable("请先登录后阅读本章全部内容。" + realText(100)))
    }

    @Test
    fun `search failure reports stuck at search`() = runBlocking {
        route("/s", """<ul><li>没有结果</li></ul>""")
        val r = SourceChecker.check(src(), polite = false, maxChapters = 1)
        assertEquals("search", r.stuckAt)
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, r.status)
        assertEquals(1, r.stages.size)
        assertTrue(!r.stages[0].passed)
    }
}
