package com.ayanami.reader

import com.ayanami.reader.core.rule.RuleAnalyzer
import com.ayanami.reader.core.rule.RuleType
import com.ayanami.reader.core.rule.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则切分的固定用例 —— 防止改代码时静默破坏语义。 */
class RuleAnalyzerTest {

    @Test
    fun `split and-or keeps order`() {
        val r = RuleAnalyzer.splitRule("a@text&&b@href")
        assertEquals(listOf("a@text", "b@href"), r.rules)
        assertEquals("&&", r.mode)

        val o = RuleAnalyzer.splitRule("a@text||b@href")
        assertEquals(listOf("a@text", "b@href"), o.rules)
        assertEquals("||", o.mode)
    }

    @Test
    fun `operators inside attr are not split`() {
        // @href?a=1&&b=2 —— 选择器里带 && 不应被切开
        val r = RuleAnalyzer.splitRule("li[data-x='a&&b']@text")
        assertEquals(1, r.rules.size)
    }

    @Test
    fun `operators inside regex are not split`() {
        val r = RuleAnalyzer.splitRule("##a&&b##x")
        assertEquals(1, r.rules.size)
    }

    @Test
    fun `split regex form`() {
        val s = RuleAnalyzer.splitRegex("div.x@text##\\s+## ")
        assertNotNull(s)
        assertEquals("div.x@text", s!!.body)
        assertEquals("\\s+", s.pattern)
        assertEquals(" ", s.replacement)
        assertTrue(!s.onlyFirst)
    }

    @Test
    fun `split regex triple hash means first only`() {
        val s = RuleAnalyzer.splitRegex("p@text##a##b###")
        assertNotNull(s)
        assertEquals("a", s!!.pattern)
        assertEquals("b", s.replacement)
        assertTrue(s.onlyFirst)
    }

    @Test
    fun `prefix detection`() {
        assertEquals(RuleType.Css, RuleAnalyzer.parsePrefix("@css:div.x").type)
        assertEquals(RuleType.Json, RuleAnalyzer.parsePrefix("@json:$.a").type)
        assertEquals(RuleType.Json, RuleAnalyzer.parsePrefix("$.a.b").type)
        assertEquals(RuleType.Js, RuleAnalyzer.parsePrefix("@js:java.ajax()").type)
        assertEquals(RuleType.XPath, RuleAnalyzer.parsePrefix("@XPath://div").type)
        assertTrue(RuleAnalyzer.parsePrefix("@@div").raw)
    }

    @Test
    fun `put and get extraction`() {
        val p = RuleAnalyzer.parsePrefix("@put:{\"k\":\"div.x@text\"} div.y@text")
        assertEquals("div.x@text", p.puts["k"])
        assertEquals("div.y@text", p.rule)

        val g = RuleAnalyzer.parsePrefix("@get:{\"k\":\"\"} @text")
        assertTrue(g.gets.contains("k"))
    }

    @Test
    fun `lenient json falls back`() {
        // 大写关键字只有宽松路径能过
        val m = LenientJson.parseStringMap("{\"a\":TRUE}")
        assertNotNull(m)
        assertEquals("true", m!!["a"]?.lowercase())

        // 严格路径能过的普通 JSON
        val n = LenientJson.parseStringMap("{\"a\":\"x\"}")
        assertEquals("x", n!!["a"])
    }

    @Test
    fun `url resolve`() {
        assertEquals(
            "https://a.com/x/y.html",
            com.ayanami.reader.core.rule.UrlHelper.resolve("https://a.com/x/z.html", "y.html")
        )
        assertEquals(
            "https://a.com/z.html",
            com.ayanami.reader.core.rule.UrlHelper.resolve("https://a.com/x/z.html", "/z.html")
        )
        assertEquals(
            "https://b.com/q",
            com.ayanami.reader.core.rule.UrlHelper.resolve("https://a.com/x/z.html", "https://b.com/q")
        )
    }
}
