package com.ayanami.reader

import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.RegexGuard
import com.ayanami.reader.core.rule.RuleError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则写错时**必须显式报错**，不能静默返回原文。
 *
 * ## 抓的 bug（v0.3.5）
 *
 * `##(unclosed`（括号没闭合）走 `RegexGuard` 时，`Regex(pattern)` 抛异常，
 * 旧代码 `catch` 住后 `return input` —— **和"正则合法但没匹配上"表现完全一样**：
 *
 * ```
 * ##(unclosed##X   → 返回原文，lastError=null   ← 用户以为是没匹配
 * ##zzz##X         → 返回原文，lastError=null   ← 真的是没匹配
 * ```
 *
 * 用户因此无法定位问题。而这条路径恰好是书源作者最常踩的坑。
 */
class BadRegexTest {

    private fun analyze(html: String, rule: String): Pair<String?, RuleError?> {
        RegexGuard.reset()
        val a = AnalyzeRule()
        a.setContent(html, "https://x/")
        val v = a.getString(rule)
        return v to a.lastError
    }

    @Test
    fun `括号没闭合要报规则语法错误`() {
        val (v, err) = analyze("<p class='t'>原文</p>", "p.t@text##(unclosed##X")
        assertEquals("内容应保留原文", "原文", v)
        assertTrue("必须报 RuleSyntaxError，实际=$err", err is RuleError.RuleSyntaxError)
        assertEquals("要能拿到出错的规则串", "(unclosed", (err as RuleError.RuleSyntaxError).rule)
    }

    @Test
    fun `非法量词也要报错`() {
        val (_, err) = analyze("<p class='t'>原文</p>", "p.t@text##a{2,1}##X")
        assertTrue("应报 RuleSyntaxError，实际=$err", err is RuleError.RuleSyntaxError)
    }

    @Test
    fun `合法但不匹配不该报错`() {
        val (v, err) = analyze("<p class='t'>原文</p>", "p.t@text##zzz##X")
        assertEquals("没匹配就返回原文", "原文", v)
        assertNull("这不是错误，不该报", err)
    }

    @Test
    fun `合法且匹配正常工作`() {
        val (v, err) = analyze("<p class='t'>编号12345</p>", "p.t@text##\\D+##")
        assertEquals("12345", v)
        assertNull(err)
    }

    @Test
    fun `编译错误不会残留到下一次调用`() {
        RegexGuard.reset()
        val a = AnalyzeRule()
        a.setContent("<p>abc</p>", "https://x/")
        a.getString("p@text##(bad##X")
        assertTrue(a.lastError is RuleError.RuleSyntaxError)

        // 下一次正常调用不该继承上一个错误
        val ok = a.getString("p@text")
        assertEquals("abc", ok)
        assertNull("上一次的编译错误不该残留", a.lastError)
    }
}
