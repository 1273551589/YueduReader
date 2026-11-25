package com.ayanami.reader

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.ContentRule
import com.ayanami.reader.core.model.SearchRule
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceSession
import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.RegexGuard
import com.ayanami.reader.core.rule.RuleError
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 「能读但字段是错的」必须被暴露出来。
 *
 * ## 抓的 bug（v0.3.5）
 *
 * 书源 `ruleSearch.name` 的正则写错（`##(unclosed`）时：
 * - `getString` 返回**原文**（不是空串）
 * - 于是 `name` 有值、整条流程跑通 → 状态显示「抽样可读」绿标
 * - 用户完全不知道书名其实是错的
 *
 * 更糟的是 `AnalyzeRule.lastError` 每次调用开头就清空，
 * 一阶段跑完 26 条 × 9 个字段后，最后一次成功就把错误痕迹冲没了。
 *
 * 修法：`onRuleError` 在**出错当下**回调，`SourceSession` 收集成 warnings，
 * 体检报告里以 `⚠` 追加。
 */
class RuleWarningTest {

    private fun serve(html: String): Pair<HttpServer, String> {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            val b = html.toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        srv.start()
        return srv to "http://127.0.0.1:${srv.address.port}"
    }

    /** 核心：回调必须在出错当下触发，不依赖事后读 lastError。 */
    @Test(timeout = 30_000)
    fun `onRuleError 在出错当下回调`() {
        RegexGuard.reset()
        val seen = ArrayList<RuleError>()
        val a = AnalyzeRule().apply { onRuleError = { seen.add(it) } }
        a.setContent("<p class='t'>内容</p>", "https://x/")

        a.getString("p.t@text##(unclosed##X")
        assertEquals("应立刻收到回调", 1, seen.size)
        assertTrue(seen[0] is RuleError.RuleSyntaxError)

        // 后续成功调用不该产生新回调
        a.getString("p.t@text")
        assertEquals("成功不该回调", 1, seen.size)
    }

    /** 一阶段里前面出错、后面成功，warnings 仍要留下记录。 */
    @Test(timeout = 60_000)
    fun `搜索阶段出错但能跑通时要留警告`() = runBlocking {
        val (srv, base) = serve("""<ul><li><a href="/b/1">书名</a></li></ul>""")
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "warn", bookSourceEnabled = true,
                searchUrl = "$base/s?k={{key}}",
                ruleSearch = SearchRule(
                    bookList = "ul > li",
                    // 这条正则写错，但 getString 会返回原文 → name 仍有值
                    name = "a@text##(unclosed##X",
                    bookUrl = "a@href"
                )
            )
            val sess = SourceSession(src)
            val hits = sess.search("x")
            assertTrue("流程本身能跑通", hits.isNotEmpty())
            assertTrue(
                "必须留下警告，实际=${sess.warnings}",
                sess.warnings.isNotEmpty()
            )
            assertTrue(
                "警告应归到 search 阶段",
                sess.warnings.any { it.first == "search" }
            )
        } finally { srv.stop(0) }
    }

    /** 完全正常的源不该产生警告（防止误报）。 */
    @Test(timeout = 60_000)
    fun `正常源不产生警告`() = runBlocking {
        val (srv, base) = serve("""<ul><li><a href="/b/1">正常书名</a></li></ul>""")
        try {
            val src = BookSource(
                bookSourceUrl = base, bookSourceName = "clean", bookSourceEnabled = true,
                searchUrl = "$base/s?k={{key}}",
                ruleSearch = SearchRule(
                    bookList = "ul > li",
                    name = "a@text##^\\s+|\\s+$##",   // 合法正则
                    bookUrl = "a@href"
                )
            )
            val sess = SourceSession(src)
            sess.search("x")
            assertTrue("不该有警告，实际=${sess.warnings}", sess.warnings.isEmpty())
        } finally { srv.stop(0) }
    }

    /** 每阶段同类错误只记一次 —— 421 章不该刷出 421 条。 */
    @Test(timeout = 30_000)
    fun `同类警告去重`() {
        val sess = SourceSession(
            BookSource(bookSourceUrl = "https://x", bookSourceName = "x")
        )
        repeat(50) {
            sess.noteWarning("toc", RuleError.RuleSyntaxError("bad"))
        }
        assertEquals("同类同阶段只留一条", 1, sess.warnings.size)

        sess.noteWarning("toc", RuleError.RuleSyntaxError("另一个"))
        assertEquals("同类同阶段仍只一条", 1, sess.warnings.size)

        sess.noteWarning("search", RuleError.RuleSyntaxError("bad"))
        assertEquals("不同阶段可以各留一条", 2, sess.warnings.size)
    }
}
