package com.ayanami.reader

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceImporter
import com.ayanami.reader.core.rule.RuleError
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书源回归台账。
 *
 * ### 它要回答的问题
 *
 * 「这一版引擎还能不能读我手上这些源？」—— 每改一次 `AnalyzeRule` /
 * `SourceSession`，都得能一眼看出哪个源从可用变成不可用、卡在哪一段。
 *
 * ### 两种跑法
 *
 * - **离线（默认）**：只做静态体检 —— JSON 能不能解析、四段规则缺不缺、
 *   有没有用到执行端不支持的语法（`@js` 调用 `Packages.*` 等）。
 *   不联网，秒级，CI 里也能跑。
 * - **联网（`-DsourceLedger.online=true`）**：真的跑四段抓取，
 *   输出分类结果。慢，且依赖站点当时的状态。
 *
 * 开关：
 * ```
 * ./gradlew testDebugUnitTest --tests "*SourceLedgerTest" -DsourceLedger.online=true
 * ```
 *
 * ### 分类口径
 *
 * | 结论 | 含义 |
 * |---|---|
 * | READABLE | 四段全通，抽检正文 >50 字且不像假正文 |
 * | NEEDS_LOGIN | 被登录墙/人机验证挡住（源本身可能没问题） |
 * | NO_CONTENT | 能连上，但某段选择器取不到东西 |
 * | UNSUPPORTED | 规则用了当前执行端不支持的语法 |
 * | CONNECT_FAILED | 站点不通 / 域名挂了 |
 * | STRUCT_INVALID | 离线静态检查都过不了（缺必填规则等） |
 */
class SourceLedgerTest {

    /** 台账里一个源的结论。 */
    data class Row(
        val name: String,
        val url: String,
        val verdict: String,
        val stage: String,
        val note: String
    )

    private fun sourceFiles(): List<File> {
        val candidates = listOf(
            File("../sources"),
            File("../../sources"),
            File("sources")
        )
        val dir = candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sorted().orEmpty()
    }

    private fun parseAll(): List<BookSource> = sourceFiles().flatMap { f ->
        val text = f.readText()
        SourceImporter.parse(text).sources
    }

    // ---------------- 离线静态检查 ----------------

    /**
     * 静态检查：不联网就能判断「这个源根本没配好」。
     *
     * 这些是引擎层的硬性要求，不满足的话联网跑也是白跑。
     */
    internal fun staticVerdict(src: BookSource): Pair<String, String>? {
        if (src.bookSourceUrl.isBlank()) return "STRUCT_INVALID" to "缺少 bookSourceUrl"
        if (src.bookSourceName.isBlank()) return "STRUCT_INVALID" to "缺少 bookSourceName"
        if (src.searchUrl.isNullOrBlank() && src.exploreUrl.isNullOrBlank()) {
            return "STRUCT_INVALID" to "searchUrl 与 exploreUrl 都为空（这个源无法发现任何书）"
        }
        val searchList = src.ruleSearch.bookList
        if (!src.searchUrl.isNullOrBlank() && searchList.isNullOrBlank()) {
            return "STRUCT_INVALID" to "有 searchUrl 但 ruleSearch.bookList 为空"
        }
        if (src.ruleToc.chapterList.isNullOrBlank()) {
            return "STRUCT_INVALID" to "ruleToc.chapterList 为空（拿不到目录）"
        }
        if (src.ruleContent.content.isNullOrBlank()) {
            return "STRUCT_INVALID" to "ruleContent.content 为空（拿不到正文）"
        }
        // 执行端不支持：@js 里调 Packages.*（方案明确禁止）
        val allRules = listOfNotNull(
            src.searchUrl, src.exploreUrl,
            searchList, src.ruleSearch.name, src.ruleSearch.author,
            src.ruleToc.chapterList, src.ruleToc.chapterName, src.ruleContent.content
        ).joinToString("\n")
        if (Regex("""Packages\s*\.\s*\w+""").containsMatchIn(allRules)) {
            return "UNSUPPORTED" to "规则里调用了 Packages.*（执行端禁止）"
        }
        return null
    }

    @Test
    fun `every bundled source passes static structure check`() {
        val sources = parseAll()
        // 仓库里还没有源时不失败 —— 这个测试只在有源的时候才有意义
        assumeTrue("sources/ 里没有书源，跳过", sources.isNotEmpty())

        val bad = sources.mapNotNull { src ->
            staticVerdict(src)?.let { (v, n) -> "${src.bookSourceName} → $v：$n" }
        }
        assertTrue(
            "以下书源静态检查不通过：\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    @Test
    fun `sfacg source declares all four stages`() {
        val sources = parseAll().filter { it.bookSourceName.contains("SF") }
        assumeTrue("没有 SF 书源，跳过", sources.isNotEmpty())
        val src = sources.first()
        assertTrue("搜索规则", !src.ruleSearch.bookList.isNullOrBlank())
        assertTrue("详情规则（tocUrl）", !src.ruleBookInfo.tocUrl.isNullOrBlank())
        assertTrue("目录规则", !src.ruleToc.chapterList.isNullOrBlank())
        assertTrue("正文规则", !src.ruleContent.content.isNullOrBlank())
    }

    // ---------------- 联网四段校验 ----------------

    /**
     * 真实跑四段，输出台账。
     *
     * 默认跳过（`-DsourceLedger.online=true` 才跑）。
     * 不默认跑的理由：站点是外部依赖，CI 上偶尔抽风会让测试无缘无故变红。
     */
    @Test
    fun `online four stage ledger`() {
        val online = System.getProperty("sourceLedger.online") == "true"
        assumeTrue("需要 -DsourceLedger.online=true 才跑联网台账", online)

        val sources = parseAll()
        assumeTrue("sources/ 里没有书源，跳过", sources.isNotEmpty())

        val rows = ArrayList<Row>()
        for (src in sources) {
            staticVerdict(src)?.let { (v, n) ->
                rows.add(Row(src.bookSourceName, src.bookSourceUrl, v, "-", n))
                return@let
            }
            val r = runBlocking { SourceChecker.check(src, polite = true, maxChapters = 2) }
            rows.add(
                Row(
                    name = src.bookSourceName,
                    url = src.bookSourceUrl,
                    verdict = r.status.name,
                    stage = r.stuckAt,
                    note = buildNote(r)
                )
            )
        }

        val report = buildString {
            appendLine("===== 书源回归台账 =====")
            appendLine("时间：${java.time.LocalDateTime.now()}")
            appendLine()
            val byVerdict = rows.groupBy { it.verdict }
            for ((verdict, list) in byVerdict.entries.sortedByDescending { it.value.size }) {
                appendLine("[$verdict] ${list.size} 个")
                list.forEach {
                    appendLine("  · ${it.name}  (卡在 ${it.stage})  ${it.note}")
                }
                appendLine()
            }
        }
        println(report)

        // 台账跑完一定要有结论 —— 全部结构非法说明解析层出问题了
        assertTrue(
            "所有源都 STRUCT_INVALID，疑似解析层回归：\n$report",
            rows.any { it.verdict != "STRUCT_INVALID" }
        )
    }

    private fun buildNote(r: SourceChecker.CheckResult): String {
        val err = r.error
        val base = when (err) {
            null -> r.detail.ifBlank { "四段通过" }
            is RuleError.SkippedStage -> "跳过了 ${err.humanMessage}"
            is RuleError.RequiresLogin -> "需登录"
            is RuleError.UnsupportedApi -> "规则不支持：${err.humanMessage}"
            else -> err.humanMessage
        }
        val warns = if (r.warnings.isNotEmpty()) " · ${r.warnings.size} 条规则警告" else ""
        return base + warns
    }

    // ---------------- 报告分类契约 ----------------

    @Test
    fun `static check flags missing content rule`() {
        val src = BookSource(
            bookSourceUrl = "https://x.test",
            bookSourceName = "缺正文",
            searchUrl = "https://x.test/search?k={{key}}"
        ).apply {
            ruleSearch.bookList = "div.item"
            ruleToc.chapterList = "a.ch"
            ruleContent.content = null
        }
        val (verdict, note) = staticVerdict(src)!!
        assertEquals("STRUCT_INVALID", verdict)
        assertTrue("应说明缺正文", note.contains("正文"))
    }

    @Test
    fun `static check flags Packages usage`() {
        val src = BookSource(
            bookSourceUrl = "https://x.test",
            bookSourceName = "用了Packages",
            searchUrl = "https://x.test/search?k={{key}}"
        ).apply {
            ruleSearch.bookList = "div.item"
            ruleToc.chapterList = "a.ch"
            ruleContent.content = "@js:Packages.java.lang.System.currentTimeMillis()"
        }
        val (verdict, _) = staticVerdict(src)!!
        assertEquals("UNSUPPORTED", verdict)
    }

    @Test
    fun `static check accepts a complete source`() {
        val src = BookSource(
            bookSourceUrl = "https://ok.test",
            bookSourceName = "完整源",
            searchUrl = "https://ok.test/s?k={{key}}"
        ).apply {
            ruleSearch.bookList = "div.item"
            ruleToc.chapterList = "a.ch"
            ruleContent.content = "div#body@html"
        }
        assertNull("完整源不该被判为非法", staticVerdict(src))
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertNull(msg: String, v: Any?) = org.junit.Assert.assertNull(msg, v)
}
