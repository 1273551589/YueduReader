package com.ayanami.reader

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.BookInfoRule
import com.ayanami.reader.core.model.ContentRule
import com.ayanami.reader.core.model.SearchRule
import com.ayanami.reader.core.model.TocRule
import com.ayanami.reader.core.pipeline.SourceSession
import com.ayanami.reader.core.rule.UrlTemplate
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * 请求管线的语义契约 —— 覆盖 v0.4.0 修掉的三个阻塞级缺陷：
 *
 * 1. 相对 `searchUrl` 没拼基地址 → 以前直接抛 `MalformedURLException`
 * 2. POST body 里的 `{{key}}` 没替换 → 以前原样发出去，服务端搜不到
 * 3. `charset: gbk` 的源仍用 UTF-8 编码关键词 → 以前服务端收到乱码
 *
 * 全部走**真实 HTTP 服务器**（127.0.0.1），不 mock —— 只有真发包
 * 才能证明"编码/替换/拼接"三件事真的落到了请求上。
 */
class RequestPipelineTest {

    // ---------------- 纯函数层 ----------------

    @Test
    fun `relative url is resolved against source url`() {
        assertEquals(
            "https://a.com/s.php?q=x",
            UrlTemplate.render("/s.php?q={{key}}", mapOf("key" to "x"), baseUrl = "https://a.com")
        )
        // 相对路径是相对 base 的「目录」部分，`/sub/` 后面接 `list/1.html`
        assertEquals(
            "https://a.com/sub/list/1.html",
            UrlTemplate.render("list/1.html", emptyMap(), baseUrl = "https://a.com/sub/")
        )
    }

    @Test
    fun `absolute url is left alone`() {
        assertEquals(
            "https://b.com/s?k=x",
            UrlTemplate.render("https://b.com/s?k={{key}}", mapOf("key" to "x"), baseUrl = "https://a.com")
        )
    }

    @Test
    fun `page variable substituted`() {
        assertEquals(
            "https://a.com/list?p=3",
            UrlTemplate.render("/list?p={{page}}", mapOf("page" to "3"), baseUrl = "https://a.com")
        )
    }

    @Test
    fun `body variables substituted`() {
        val out = UrlTemplate.render(
            "searchkey={{key}}&p={{page}}",
            mapOf("key" to "三体", "page" to "2"),
            encode = true
        )
        assertTrue("应含编码后的关键词：$out", out.startsWith("searchkey=%E4%B8%89%E4%BD%93"))
        assertTrue("页码应替换：$out", out.endsWith("&p=2"))
    }

    @Test
    fun `gbk charset encodes key in gbk not utf8`() {
        val gbk = UrlTemplate.render("q={{key}}", mapOf("key" to "中文"), charset = "gbk")
        val utf8 = UrlTemplate.render("q={{key}}", mapOf("key" to "中文"), charset = "utf-8")
        assertEquals("GBK 里「中文」是 %D6%D0%CE%C4", "q=%D6%D0%CE%C4", gbk)
        assertEquals("UTF-8 里「中文」是 %E4%B8%AD%E6%96%87", "q=%E4%B8%AD%E6%96%87", utf8)
    }

    @Test
    fun `space encodes as plus for form posts`() {
        assertEquals("a=b+c", UrlTemplate.render("a={{key}}", mapOf("key" to "b c")))
    }

    @Test
    fun `split url option only at top level`() {
        // 逗号在 url 里（ids=1,2,3），不能拆
        val (u1, o1) = UrlTemplate.splitUrlOption("https://a.com/x?ids=1,2,3")
        assertEquals("https://a.com/x?ids=1,2,3", u1)
        assertEquals(null, o1)

        // 真正的 url,{json}
        val (u2, o2) = UrlTemplate.splitUrlOption("""https://a.com/s,{"method":"POST"}""")
        assertEquals("https://a.com/s", u2)
        assertEquals("""{"method":"POST"}""", o2)
    }

    // ---------------- 真实 HTTP 层 ----------------

    private class Server {
        val received = mutableListOf<Triple<String, String, String>>() // method, path, body
        lateinit var server: HttpServer
        var port = 0

        fun start(routes: Map<String, (HttpExchange) -> Unit>) {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                val path = ex.requestURI.toString()
                val body = ex.requestBody.use { String(it.readBytes(), Charsets.ISO_8859_1) }
                received.add(Triple(ex.requestMethod, path, body))
                val handler = routes[path] ?: routes.entries.firstOrNull {
                    path.startsWith(it.key)
                }?.value
                if (handler == null) {
                    ex.sendResponseHeaders(404, -1)
                } else handler(ex)
                ex.close()
            }
            server.start()
            port = server.address.port
        }

        val base: String get() = "http://127.0.0.1:$port"

        fun stop() = server.stop(0)
    }

    private fun ok(ex: HttpExchange, html: String, charset: String = "utf-8") {
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=$charset")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private val searchHtml = """
        <ul>
          <li class="odd"><a href="/book/1.html">三体</a></li>
          <li class="odd"><a href="/book/2.html">球状闪电</a></li>
        </ul>
    """.trimIndent()

    private fun source(base: String, searchUrl: String) = BookSource(
        bookSourceUrl = base,
        bookSourceName = "本地测试源",
        searchUrl = searchUrl,
        ruleSearch = SearchRule(
            bookList = "class.odd",
            name = "tag.a@text",
            bookUrl = "tag.a@href"
        ),
        ruleBookInfo = BookInfoRule(name = "tag.h1@text"),
        ruleToc = TocRule(chapterList = "class.ch", chapterName = "tag.a@text", chapterUrl = "tag.a@href"),
        ruleContent = ContentRule(content = "id.txt@text")
    )

    @Test
    fun `relative search url is actually fetched`() = runBlocking {
        val srv = Server()
        srv.start(mapOf("/s.php" to { ex -> ok(ex, searchHtml) }))
        try {
            // 书源写的是相对地址 —— 这是以前必然失败的场景
            val src = source(srv.base, "/s.php?q={{key}}&p={{page}}")
            val session = SourceSession(src)
            val hits = session.search("三体")

            assertEquals("相对地址应能搜到 2 条", 2, hits.size)
            assertEquals("三体", hits[0].name)
            assertEquals("${srv.base}/book/1.html", hits[0].bookUrl)
            assertTrue(
                "服务端应收到展开后的 URL",
                srv.received[0].second.contains("/s.php?q=")
            )
            assertTrue("页码应被替换：${srv.received[0].second}", srv.received[0].second.contains("&p=1"))
        } finally { srv.stop() }
    }

    @Test
    fun `post body key is substituted`() = runBlocking {
        val srv = Server()
        srv.start(mapOf("/search" to { ex -> ok(ex, searchHtml) }))
        try {
            val src = source(
                srv.base,
                """/search,{"method":"POST","body":"searchkey={{key}}&page={{page}}"}"""
            )
            val session = SourceSession(src)
            val hits = session.search("三体")

            assertEquals("POST 搜索应拿到结果", 2, hits.size)
            val (method, _, body) = srv.received[0]
            assertEquals("POST", method)
            assertTrue("body 里的关键词必须被替换：$body", body.contains("searchkey=%E4%B8%89%E4%BD%93"))
            assertTrue("body 里的页码必须被替换：$body", body.contains("page=1"))
        } finally { srv.stop() }
    }

    @Test
    fun `gbk source encodes key with gbk in query`() = runBlocking {
        val srv = Server()
        srv.start(mapOf("/s" to { ex -> ok(ex, searchHtml) }))
        try {
            val src = source(srv.base, """/s?q={{key}},{"charset":"gbk"}""")
            SourceSession(src).search("中文")

            val raw = srv.received[0].second
            assertTrue("查询串应按 GBK 编码，实际=$raw", raw.contains("%D6%D0%CE%C4"))
            // 反向确认没被 UTF-8 编（那会变成 %E4%B8%AD...）
            assertTrue("不应是 UTF-8 编码，实际=$raw", !raw.contains("%E4%B8%AD"))
        } finally { srv.stop() }
    }

    @Test
    fun `gbk source decodes response correctly`() = runBlocking {
        val srv = Server()
        // 响应体声明 GBK —— 若引擎按 UTF-8 解，书名会变乱码
        srv.start(mapOf("/s" to { ex ->
            val gbkBytes = searchHtml.toByteArray(charset("GBK"))
            ex.responseHeaders.add("Content-Type", "text/html; charset=gbk")
            ex.sendResponseHeaders(200, gbkBytes.size.toLong())
            ex.responseBody.use { it.write(gbkBytes) }
        }))
        try {
            val src = source(srv.base, """/s?q={{key}},{"charset":"gbk"}""")
            val hits = SourceSession(src).search("三体")
            assertEquals(2, hits.size)
            assertEquals("GBK 响应应正确解码", "三体", hits[0].name)
        } finally { srv.stop() }
    }

    @Test
    fun `toc url is absolutized before fetching`() = runBlocking {
        val srv = Server()
        srv.start(
            mapOf(
                "/book/1.html" to { ex ->
                    ok(ex, """<h1>测试书</h1><a class="btn" href="/list/1.html">目录</a>""")
                },
                "/list/1.html" to { ex ->
                    ok(ex, """<ul><li class="ch"><a href="/c/1.html">第1章</a></li></ul>""")
                }
            )
        )
        try {
            val src = source(srv.base, "/s?q={{key}}").copy(
                ruleBookInfo = BookInfoRule(name = "tag.h1@text", tocUrl = "class.btn@href")
            )
            val session = SourceSession(src)
            val info = session.bookInfo("${srv.base}/book/1.html")
            assertNotNull(info)
            assertEquals("目录地址应拼成绝对地址", "${srv.base}/list/1.html", info!!.tocUrl)

            val chapters = session.toc(info)
            assertEquals(1, chapters.size)
            assertEquals("章节地址也应绝对化", "${srv.base}/c/1.html", chapters[0].url)
        } finally { srv.stop() }
    }

    @Test
    fun `explore categories parsed`() {
        val src = BookSource(
            bookSourceUrl = "https://a.com",
            bookSourceName = "x",
            exploreUrl = "玄幻::/list/xhuan.html\n都市::/list/dushi.html"
        )
        val cats = SourceSession(src).exploreCategories()
        assertEquals(2, cats.size)
        assertEquals("玄幻" to "/list/xhuan.html", cats[0])
        assertEquals("都市" to "/list/dushi.html", cats[1])
    }

    @Test
    fun `explore fetches and parses list`() = runBlocking {
        val srv = Server()
        srv.start(mapOf("/list/xhuan.html" to { ex -> ok(ex, searchHtml) }))
        try {
            val src = source(srv.base, "/s?q={{key}}").copy(
                exploreUrl = "玄幻::/list/xhuan.html",
                ruleExplore = com.ayanami.reader.core.model.ExploreRule(
                    bookList = "class.odd",
                    name = "tag.a@text",
                    bookUrl = "tag.a@href"
                )
            )
            val hits = SourceSession(src).explore("/list/xhuan.html")
            assertEquals(2, hits.size)
            assertEquals("${srv.base}/book/2.html", hits[1].bookUrl)
        } finally { srv.stop() }
    }

    @Test
    fun `url encoded post body keyword round trips`() = runBlocking {
        val srv = Server()
        srv.start(mapOf("/search" to { ex -> ok(ex, searchHtml) }))
        try {
            val src = source(
                srv.base,
                """/search,{"method":"POST","body":"k={{key}}"}"""
            )
            SourceSession(src).search("三 体")
            val body = srv.received[0].third
            // ISO-8859-1 读回来还原成百分号串再解码
            val decoded = URLDecoder.decode(body.substringAfter("k="), "UTF-8")
            assertEquals("含空格的词应能往返", "三 体", decoded)
        } finally { srv.stop() }
    }
}
