package com.ayanami.reader

import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.RuleSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legado **默认选择器语法** + `@` 链式下钻 的语义契约测试。
 *
 * 这一组是 v0.4.0 的验收核心：43 条实测书源搜不到内容，
 * 根因全在下面这些用例覆盖的语法上。每条都对应一个真实书源写法。
 */
class LegadoSelectorTest {

    private fun rule(html: String) = AnalyzeRule().setContent(html, "https://a.com/book/1.html")

    // ---------------- class / id / tag / text ----------------

    @Test
    fun `class accessor matches`() {
        val html = """
            <div class="odd">一</div>
            <div class="even">二</div>
        """.trimIndent()
        assertEquals("一", rule(html).getString("class.odd@text"))
        assertEquals("二", rule(html).getString("class.even@text"))
    }

    @Test
    fun `class accessor is substring based`() {
        // Legado 的 class.x 是 [class*=x]，能命中 book-item
        val html = """<li class="book-item">命中</li>"""
        assertEquals("命中", rule(html).getString("class.book@text"))
    }

    @Test
    fun `id accessor matches`() {
        val html = """<div id="main">正文区</div>"""
        assertEquals("正文区", rule(html).getString("id.main@text"))
    }

    @Test
    fun `tag accessor matches`() {
        val html = """<h1>标题</h1><h2>副标题</h2>"""
        assertEquals("标题", rule(html).getString("tag.h1@text"))
    }

    @Test
    fun `text accessor matches by own text`() {
        val html = """<span>作者：张三</span><span>译者：李四</span>"""
        assertEquals("作者：张三", rule(html).getString("text.作者@text"))
    }

    @Test
    fun `text accessor falls back to full text`() {
        // 文字包在子 span 里，父节点 ownText 为空
        val html = """<div class="m"><span>状态：连载中</span></div>"""
        val v = rule(html).getString("text.连载中@text").orEmpty()
        assertTrue("应命中父节点或子节点，实际=$v", v.contains("连载中"))
    }

    // ---------------- 索引 ----------------

    @Test
    fun `position index picks nth`() {
        val html = (1..5).joinToString("") { """<li class="i">第${it}章</li>""" }
        assertEquals("第1章", rule(html).getString("class.i.0@text"))
        assertEquals("第3章", rule(html).getString("class.i.2@text"))
    }

    @Test
    fun `negative index counts from end`() {
        val html = (1..5).joinToString("") { """<li class="i">第${it}章</li>""" }
        assertEquals("第5章", rule(html).getString("class.i.-1@text"))
        assertEquals("第4章", rule(html).getString("class.i.-2@text"))
    }

    @Test
    fun `exclude index removes one`() {
        val html = (1..3).joinToString("") { """<li class="i">第${it}章</li>""" }
        // !0 排除第一条 → 剩 [2,3]，取第一个 = 第2章
        assertEquals("第2章", rule(html).getString("class.i!0@text"))
    }

    @Test
    fun `range index takes slice`() {
        val html = (1..6).joinToString("") { """<li class="i">第${it}章</li>""" }
        val list = rule(html).getStringList("class.i[1,3]@text")
        assertEquals(listOf("第2章", "第3章", "第4章"), list)
    }

    @Test
    fun `colon range index is supported`() {
        val html = (1..6).joinToString("") { """<li class="i">第${it}章</li>""" }
        val list = rule(html).getStringList("class.i[1:2]@text")
        assertEquals(listOf("第2章", "第3章"), list)
    }

    @Test
    fun `out of range index yields empty not crash`() {
        val html = """<li class="i">只有一条</li>"""
        assertEquals("", rule(html).getString("class.i.99@text"))
    }

    // ---------------- @ 链式下钻（核心 bug 1） ----------------

    @Test
    fun `at chain descends step by step`() {
        // 这是最典型的书源写法：列表项 → 里面的链接 → 取 href
        val html = """
            <ul>
              <li class="odd">
                <h4><a href="/book/100.html">第一本</a></h4>
              </li>
              <li class="odd">
                <h4><a href="/book/200.html">第二本</a></h4>
              </li>
            </ul>
        """.trimIndent()

        assertEquals("第一本", rule(html).getString("class.odd.0@tag.a@text"))
        assertEquals(
            "https://a.com/book/100.html",
            rule(html).getString("class.odd.0@tag.a@href")
        )
        assertEquals("第二本", rule(html).getString("class.odd.1@tag.a@text"))
    }

    @Test
    fun `at chain three levels`() {
        val html = """
            <div class="list">
              <div class="item">
                <div class="title"><span class="name">三体</span></div>
              </div>
            </div>
        """.trimIndent()
        assertEquals("三体", rule(html).getString("class.list@class.item.0@class.name@text"))
    }

    @Test
    fun `at chain picks element list then extracts all`() {
        val html = """
            <ul>
              <li class="odd"><a href="/a.html">甲</a></li>
              <li class="odd"><a href="/b.html">乙</a></li>
            </ul>
        """.trimIndent()
        val list = rule(html).getStringList("class.odd@tag.a@text")
        assertEquals(listOf("甲", "乙"), list)
    }

    @Test
    fun `at inside bracket is not split`() {
        // 选择器属性值里带 @，不能被当成链分隔符
        val html = """<li data-mail="a@b.com">邮箱项</li>"""
        assertEquals("邮箱项", rule(html).getString("tag.li[data-mail='a@b.com']@text"))
    }

    @Test
    fun `at inside regex is not split`() {
        // 正则以 ## 包裹，里面的 @ 不能被切
        val html = """<p>x@y.com</p>"""
        val v = rule(html).getString("tag.p@text##@##_")
        assertEquals("x_y.com", v)
    }

    // ---------------- children ----------------

    @Test
    fun `children pseudo tag`() {
        val html = """<div id="box"><p>一</p><p>二</p><span>三</span></div>"""
        val list = rule(html).getStringList("id.box@children@text")
        assertEquals(listOf("一", "二", "三"), list)
    }

    @Test
    fun `children with index`() {
        val html = """<div id="box"><p>一</p><p>二</p><span>三</span></div>"""
        assertEquals("二", rule(html).getString("id.box@children.1@text"))
    }

    // ---------------- 普通 CSS 仍然可用 ----------------

    @Test
    fun `plain css still works`() {
        val html = """<div class="a"><span class="b">文字</span></div>"""
        assertEquals("文字", rule(html).getString("div.a > span.b@text"))
        assertEquals("文字", rule(html).getString("div.a span@text"))
    }

    @Test
    fun `plain css with trailing index`() {
        val html = """<p class="x a">第一段</p><p class="x b">第二段</p>"""
        assertEquals("第二段", rule(html).getString("p.x.1@text"))
    }

    @Test
    fun `leading child combinator`() {
        val html = """<div id="r"><a>直系</a><span><a>孙级</a></span></div>"""
        val v = rule(html).getString("id.r@>a@text")
        assertEquals("直系", v)
    }

    // ---------------- 后缀 <js>（核心 bug 2） ----------------

    @Test
    fun `suffix js gets prior result`() {
        val html = """<h1>【连载】三体</h1>"""
        val a = rule(html)
        // 没有 JS 运行时 → 显式报 UnsupportedApi，而不是静默返回空
        val v = a.getString("tag.h1@text<js>result = result.replace(/【.*】/, '')</js>")
        assertEquals("", v)
        assertTrue("应报 UnsupportedApi", a.lastError is com.ayanami.reader.core.rule.RuleError.UnsupportedApi)
    }

    // ---------------- 取值符白名单 ----------------

    @Test
    fun `extractor detection`() {
        assertTrue(RuleSteps.isExtractor("text"))
        assertTrue(RuleSteps.isExtractor("href"))
        assertTrue(RuleSteps.isExtractor("data-src"))
        assertTrue(RuleSteps.isExtractor("abs:href"))
        assertTrue(!RuleSteps.isExtractor("div"))
        assertTrue(!RuleSteps.isExtractor("odd"))
    }

    @Test
    fun `selector steps drop last extractor only`() {
        val steps = RuleSteps.splitSteps("class.odd.0@tag.a@href")
        assertEquals(listOf("class.odd.0", "tag.a", "href"), steps)
        assertEquals("href", RuleSteps.extractorOf(steps))
        assertEquals(listOf("class.odd.0", "tag.a"), RuleSteps.selectorSteps(steps))
    }

    @Test
    fun `data-src is absolutized`() {
        val html = """<img class="c" data-src="/img/1.jpg">"""
        assertEquals("https://a.com/img/1.jpg", rule(html).getString("class.c@data-src"))
    }

    // ---------------- 非法选择器要报错，不能静默 ----------------

    @Test
    fun `bad selector reports syntax error`() {
        val a = rule("<p>x</p>")
        val v = a.getString("div[[[bad@text")
        assertNull("非法选择器应返回 null", v)
        assertTrue("应报 RuleSyntaxError", a.lastError is com.ayanami.reader.core.rule.RuleError.RuleSyntaxError)
    }

    // ---------------- 解析器单测 ----------------

    @Test
    fun `parse spec forms`() {
        val c = RuleSteps.parse("class.odd.0")
        assertEquals("class", c.accessor)
        assertEquals("odd", c.arg)
        assertEquals(1, c.filters.size)

        val t = RuleSteps.parse("tag.tr!0")
        assertEquals("tag", t.accessor)
        assertEquals("tr", t.arg)
        assertTrue(t.filters[0] is RuleSteps.Filter.Exclude)

        val g = RuleSteps.parse("tag.div[1,3]")
        assertTrue(g.filters[0] is RuleSteps.Filter.Range)

        val ch = RuleSteps.parse("children")
        assertEquals("children", ch.accessor)
        assertTrue(ch.filters.isEmpty())

        val ch2 = RuleSteps.parse("children.2")
        assertEquals("children", ch2.accessor)
        assertEquals(1, ch2.filters.size)

        val css = RuleSteps.parse("div.list > a")
        assertEquals("css", css.accessor)
        assertEquals("div.list > a", css.arg)
    }

    @Test
    fun `split steps handles regex and mustache`() {
        assertEquals(
            listOf("tag.p", "text"),
            RuleSteps.splitSteps("tag.p@text")
        )
        // {{$.x@y}} 内部的 @ 不切，但花括号**前面**那个 @ 是合法的链分隔符
        assertEquals(
            listOf("div", "{{$.x@y}}"),
            RuleSteps.splitSteps("div@{{$.x@y}}")
        )
    }
}
