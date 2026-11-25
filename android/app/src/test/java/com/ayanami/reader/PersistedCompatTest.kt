package com.ayanami.reader

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ayanami.reader.data.PersistedCheckResult
import com.ayanami.reader.core.pipeline.SourceChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版本写出的 `checkResults.json` 的向后兼容。
 *
 * 背景：v0.3.5 之前 `error` 是 sealed class，Gson 无法还原抽象类型，
 * **整个 Map 反序列化成 null** —— 一条带 error 就导致所有源的体检结果丢失。
 *
 * 修复后改成扁平 DTO（`kind`/`arg`/`code`，都有默认值）。
 * 但**升级用户机器上还躺着旧格式的文件**，必须能读、不能整表丢。
 */
class PersistedCompatTest {

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, PersistedCheckResult>>() {}.type

    private fun parse(json: String): Map<String, PersistedCheckResult>? =
        runCatching { gson.fromJson<Map<String, PersistedCheckResult>>(json, type) }.getOrNull()

    @Test
    fun `旧格式全正常也能读`() {
        val json = """{"s1":{"sourceUrl":"s1","status":"READABLE","elapsedMs":100,
            "stuckAt":"done","detail":"ok"}}""".trimIndent()
        val m = parse(json)
        assertNotNull("旧格式不应解析成 null", m)
        assertEquals(1, m!!.size)
        assertEquals(SourceChecker.UiStatus.READABLE, m["s1"]!!.toResult().status)
    }

    /**
     * 核心回归：**一条带 error，不能连累其他源**。
     *
     * 这正是修复前的表现：整个 Map 变 null，好的那条也一起没了。
     */
    @Test
    fun `旧格式有一条带 error 时其他源不能丢`() {
        val json = """{
          "s1":{"sourceUrl":"s1","status":"READABLE","elapsedMs":100,"stuckAt":"done"},
          "s2":{"sourceUrl":"s2","status":"CONNECT_FAILED","elapsedMs":5,"stuckAt":"search",
                "error":{"stage":"toc.chapterList"}}
        }""".trimIndent()
        val m = parse(json)
        assertNotNull("带 error 的旧格式不应解析成 null（这会让整表丢失）", m)
        // 注意：Gson 在 JSON 对象的 value 缺字段时仍能建对象，
        // 但这里 map 的 key 来自 JSON 的 key，不该是 null。
        println("DEBUG keys=" + m!!.keys.map { it ?: "NULL" })
        assertEquals("两个源都应保留", 2, m.size)

        val r1 = m["s1"]!!.toResult()
        assertEquals("好源的状态不能丢", SourceChecker.UiStatus.READABLE, r1.status)

        val r2 = m["s2"]!!.toResult()
        assertEquals(SourceChecker.UiStatus.CONNECT_FAILED, r2.status)
        assertNotNull("坏源的 error 应有个兜底值，不能是 null", r2.error)
    }

    @Test
    fun `新格式往返不丢字段`() {
        val orig = SourceChecker.CheckResult(
            sourceUrl = "x",
            status = SourceChecker.UiStatus.READABLE,
            elapsedMs = 1234,
            stuckAt = "done",
            detail = "四步通（5 次请求）",
            sampleBook = "某书",
            warnings = listOf("search" to com.ayanami.reader.core.rule.RuleError.RuleSyntaxError("##(unclosed"))
        )
        val json = gson.toJson(mapOf("x" to PersistedCheckResult.from(orig)))
        val back = parse(json)!!["x"]!!.toResult()

        assertEquals(orig.status, back.status)
        assertEquals(orig.elapsedMs, back.elapsedMs)
        assertEquals(orig.sampleBook, back.sampleBook)
        assertEquals("警告条数要保住", 1, back.warnings.size)
        assertEquals("search", back.warnings[0].first)
        assertTrue(
            "出错的规则串要能还原，否则 UI 上「出错的规则」是空的",
            back.warnings[0].second is com.ayanami.reader.core.rule.RuleError.RuleSyntaxError
        )
        assertEquals(
            "##(unclosed",
            (back.warnings[0].second as com.ayanami.reader.core.rule.RuleError.RuleSyntaxError).rule
        )
    }

    @Test
    fun `空文件与空 map 都返回空而不是崩`() {
        assertEquals(0, parse("{}")!!.size)
        assertEquals(null, parse("not json at all"))
    }
}
