package com.ayanami.reader

import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.rule.LenientJson
import com.ayanami.reader.core.rule.RuleError
import com.ayanami.reader.core.rule.RuleErrorSnapshot
import com.ayanami.reader.data.PersistedCheckResult
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检结果的持久化往返。
 *
 * ## 抓的 bug（v0.3.5）
 *
 * `CheckResult.error` 是 sealed class `RuleError`。Gson 直接反序列化
 * `Map<String, CheckResult>` 时，遇到 error 字段无法实例化抽象类，
 * **整个 Map 变成 null** —— 所有书源的体检结果一起丢，包括"抽样可读"的那些。
 *
 * 实测：一条带 error，另一条 READABLE，读回来两条都没了。
 * 用户看到的现象是「体检完显示抽样可读，重启后全变未验证」。
 */
class CheckResultPersistTest {

    private fun roundtrip(map: Map<String, SourceChecker.CheckResult>): Map<String, SourceChecker.CheckResult>? {
        // 模拟 Repository 的做法：写 PersistedCheckResult，读回来再拼
        val persisted = map.mapValues { (_, v) -> PersistedCheckResult.from(v) }
        val json = LenientJson.lenient.toJson(persisted)
        val back = LenientJson.lenient.fromJson<Map<String, PersistedCheckResult>>(
            json, object : TypeToken<Map<String, PersistedCheckResult>>() {}.type
        ) ?: return null
        return back.mapValues { (_, v) -> v.toResult() }
    }

    @Test
    fun `带错误的体检结果能往返`() {
        val map = mapOf(
            "https://a.com" to SourceChecker.CheckResult(
                "https://a.com", SourceChecker.UiStatus.NO_CONTENT, 100, "toc",
                "未取到内容", null, RuleError.SelectorEmpty("toc.chapterList")
            )
        )
        val back = roundtrip(map)
        assertNotNull("不能整体变成 null", back)
        assertEquals(1, back!!.size)
        assertEquals(SourceChecker.UiStatus.NO_CONTENT, back["https://a.com"]!!.status)
        assertEquals("阶段要还原", "toc.chapterList",
            (back["https://a.com"]!!.error as RuleError.SelectorEmpty).stage)
    }

    /**
     * 核心回归：一条带 error + 一条 READABLE，**两条都必须还在**。
     * 修复前整个 Map 为 null，这条必挂。
     */
    @Test
    fun `一条出错不能连带丢掉其他源`() {
        val map = mapOf(
            "https://a.com" to SourceChecker.CheckResult(
                "https://a.com", SourceChecker.UiStatus.NO_CONTENT, 100, "toc",
                "未取到内容", null, RuleError.SelectorEmpty("toc.chapterList")
            ),
            "https://b.com" to SourceChecker.CheckResult(
                "https://b.com", SourceChecker.UiStatus.READABLE, 200, "done",
                "四步通", "某书", null
            )
        )
        val back = roundtrip(map)
        assertNotNull("不能整体变成 null", back)
        assertEquals("两个源都要在", 2, back!!.size)
        assertEquals("可读那条不能被连累",
            SourceChecker.UiStatus.READABLE, back["https://b.com"]!!.status)
    }

    @Test
    fun `所有错误类型都能往返`() {
        val all = listOf(
            RuleError.NetworkTimeout("请求超时"),
            RuleError.NetworkRefused("域名解析失败"),
            RuleError.HttpStatus(403),
            RuleError.SelectorEmpty("search.bookList"),
            RuleError.RuleSyntaxError("div.x@text##(a+"),
            RuleError.JsThrown("undefined is not a function"),
            RuleError.JsTimeout,
            RuleError.UnsupportedApi("startBrowser"),
            RuleError.SkippedStage("没有配置搜索"),
            RuleError.RequiresLogin("检测到登录墙"),
            RuleError.DecodeError("GBK 解码失败"),
            RuleError.RegexTimeout("(.*a){30}")
        )
        all.forEach { err ->
            val snap = RuleErrorSnapshot.from(err)
            assertNotNull("${err.javaClass.simpleName} 快照不能为空", snap)
            val back = snap!!.toRuleError()
            assertEquals(
                "${err.javaClass.simpleName} 类型要一致",
                err.javaClass, back.javaClass
            )
            assertEquals(
                "${err.javaClass.simpleName} 提示语要一致",
                err.humanMessage, back.humanMessage
            )
        }
    }

    @Test
    fun `HttpStatus 保留状态码`() {
        val back = RuleErrorSnapshot.from(RuleError.HttpStatus(429))!!.toRuleError()
        assertEquals(429, (back as RuleError.HttpStatus).code)
    }

    @Test
    fun `出错的规则串要能读出来给 UI`() {
        val e = RuleError.RuleSyntaxError("div.x@text##(a+")
        assertEquals("div.x@text##(a+", e.offendingRule)
        assertNull("不是规则类错误就不该有", RuleError.JsTimeout.offendingRule)
    }

    /** 非致命警告也要能往返（否则重启后"可读·有警告"退回普通绿标）。 */
    @Test
    fun `警告能往返且保留规则串`() {
        val map = mapOf(
            "https://a.com" to SourceChecker.CheckResult(
                "https://a.com", SourceChecker.UiStatus.READABLE, 200, "done",
                "四步通 · ⚠ search: 规则语法错误", "某书", null,
                warnings = listOf("search" to RuleError.RuleSyntaxError("(unclosed"))
            )
        )
        val back = roundtrip(map)
        assertNotNull(back)
        val r = back!!["https://a.com"]!!
        assertEquals("警告条数要保留", 1, r.warnings.size)
        assertEquals("阶段要保留", "search", r.warnings[0].first)
        assertEquals(
            "规则串要保留（UI 要显示它）",
            "(unclosed", r.warnings[0].second.offendingRule
        )
    }

    @Test
    fun `未知错误类型不崩`() {
        val back = RuleErrorSnapshot("SomethingNew", "x").toRuleError()
        assertTrue(back is RuleError.DecodeError)
    }
}
