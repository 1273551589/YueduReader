package com.ayanami.reader

import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.JsBridge
import com.ayanami.reader.core.rule.JsRuntime
import com.ayanami.reader.core.rule.RegexGuard
import com.ayanami.reader.core.rule.RuleError
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `@js` 与 正则超时 的纯 JVM 单测。
 *
 * 不联网：bridge 用假实现。所以这套测试能在没有 Android 设备的机器上跑。
 */
class JsAndPagingTest {

    /** 假的抓取桥：把 url 当 key 查表。 */
    private class FakeBridge(
        private val pages: Map<String, String> = emptyMap(),
        private val elements: Map<String, List<Element>> = emptyMap()
    ) : JsBridge {
        val logs = ArrayList<String>()
        val vars = HashMap<String, String>()

        override fun ajax(url: String, headers: Map<String, String>?): String? = pages[url]
        override fun log(msg: String) { logs.add(msg) }
        override fun base64Encode(data: String, flags: Int): String =
            Base64.getEncoder().encodeToString(data.toByteArray())
        override fun base64Decode(data: String, flags: Int): String =
            runCatching { String(Base64.getDecoder().decode(data)) }.getOrDefault("")
        override fun encodeUri(s: String, enc: String): String =
            java.net.URLEncoder.encode(s, enc)
        override fun currentContent(): Any? = null
        override fun getElements(rule: String): List<Element> = elements[rule] ?: emptyList()
        override fun putVar(key: String, value: String) { vars[key] = value }
        override fun getVar(key: String): String? = vars[key]
    }

    // ---------------- @js ----------------

    @Test
    fun `js returns last expression`() {
        val rt = JsRuntime(FakeBridge())
        assertEquals("hello", rt.eval("'hello'"))
    }

    @Test
    fun `js result variable wins`() {
        val rt = JsRuntime(FakeBridge())
        assertEquals("fromvar", rt.eval("result = 'fromvar'; 'ignored'"))
    }

    @Test
    fun `js string ops`() {
        val rt = JsRuntime(FakeBridge())
        assertEquals("ABC", rt.eval("'abc'.toUpperCase()"))
        assertEquals("a-b", rt.eval("['a','b'].join('-')"))
        assertEquals("3", rt.eval("String('abc'.length)"))
    }

    @Test
    fun `js java base64 roundtrip`() {
        val rt = JsRuntime(FakeBridge())
        val enc = rt.eval("java.base64Encode('你好')")
        assertNotNull(enc)
        val dec = rt.eval("java.base64Decode('$enc')")
        assertEquals("你好", dec)
    }

    @Test
    fun `js java put and get share vars`() {
        val bridge = FakeBridge()
        val rt = JsRuntime(bridge)
        rt.eval("java.put('k', 'v1')")
        assertEquals("v1", bridge.vars["k"])
        // 新运行时通过 bridge 仍能读到（模拟跨阶段）
        val rt2 = JsRuntime(bridge)
        assertEquals("v1", rt2.eval("java.get('k')"))
    }

    @Test
    fun `js java ajax uses bridge`() {
        val bridge = FakeBridge(mapOf("https://x/api" to """{"n":42}"""))
        val rt = JsRuntime(bridge)
        assertEquals("42", rt.eval("JSON.parse(java.ajax('https://x/api')).n"))
    }

    @Test
    fun `js java log collected`() {
        val bridge = FakeBridge()
        val rt = JsRuntime(bridge)
        rt.eval("java.log('hello', 'world')")
        assertEquals(1, bridge.logs.size)
        assertEquals("hello world", bridge.logs[0])
    }

    @Test
    fun `js unsupported api is reported not silently empty`() {
        val rt = JsRuntime(FakeBridge())
        val out = rt.eval("java.startBrowser('x')")
        assertNull(out)
        val err = rt.lastError
        assertNotNull("必须报错而不是静默返回空串", err)
        assertTrue("应归类为 JsThrown 或 UnsupportedApi", err is RuleError.JsThrown)
        assertEquals("startBrowser", rt.lastUnsupported)
    }

    @Test
    fun `js infinite loop times out`() {
        val rt = JsRuntime(FakeBridge(), timeoutMs = 400)
        val start = System.currentTimeMillis()
        val out = rt.eval("while(true){}")
        val cost = System.currentTimeMillis() - start
        assertNull(out)
        assertTrue("应判为 JsTimeout，实际=${rt.lastError}", rt.lastError is RuleError.JsTimeout)
        assertTrue("应在 400ms 附近返回，实际 ${cost}ms", cost < 3000)
    }

    @Test
    fun `js syntax error is JsThrown`() {
        val rt = JsRuntime(FakeBridge())
        assertNull(rt.eval("this is not js ("))
        assertTrue(rt.lastError is RuleError.JsThrown)
    }

    @Test
    fun `analyze rule runs js on html content`() {
        val bridge = FakeBridge()
        val a = AnalyzeRule()
        a.jsRuntime = JsRuntime(bridge)
        a.setContent("<div class='t'>标题</div>", "https://x/")
        val v = a.getString("@js:java.getString('.t@text')")
        assertEquals("标题", v)
    }

    @Test
    fun `analyze rule js can use regex chain`() {
        val a = AnalyzeRule()
        a.jsRuntime = JsRuntime(FakeBridge())
        a.setContent("<p>编号 12345 号</p>", "https://x/")
        // ## 在 @js 结果之后继续作用
        val v = a.getString("@js:java.getString('p@text')##\\D##")
        assertEquals("12345", v?.trim())
    }

    // ---------------- 正则超时 ----------------

    /**
     * ⚠️ 用 `(.*a){30}` 而不是教科书上的 `(a+)+$`。
     *
     * 实测（JDK 17）：
     * - `(a+)+$`、`(a|aa)+$`、`(a+)+b` 都在 1–3ms 返回 —— **JDK 9+ 对这类做了度量化优化**
     * - `(.*a){30}` 在 100 字符输入上要 **15.4 秒**
     *
     * 所以"灾难性回溯"要拿真正会回溯的形态测，否则测试会假绿。
     */
    @Test
    fun `catastrophic regex times out instead of hanging`() {
        RegexGuard.reset()
        RegexGuard.timeoutMs = 400
        val input = "a".repeat(100)
        val start = System.currentTimeMillis()
        val out = RegexGuard.replaceTimeout(input, "(.*a){30}", "x", onlyFirst = false)
        val cost = System.currentTimeMillis() - start
        assertNull("灾难性回溯应判超时，实际返回长度=${out?.length}", out)
        assertTrue("应在 400ms 上限附近返回，实际 ${cost}ms", cost < 4000)
        assertTrue("应记一次泄漏", RegexGuard.abandoned.get() >= 1)
        RegexGuard.timeoutMs = 800
    }

    @Test
    fun `normal regex still works through guard`() {
        RegexGuard.reset()
        val out = RegexGuard.replaceTimeout("编号 12345", "\\D+", "", onlyFirst = false)
        assertEquals("12345", out)
    }

    @Test
    fun `guard nonFirst replaces all`() {
        RegexGuard.reset()
        val out = RegexGuard.replaceTimeout("a1b2c3", "\\d", "#", onlyFirst = false)
        assertEquals("a#b#c#", out)
    }

    @Test
    fun `guard onlyFirst replaces one`() {
        RegexGuard.reset()
        val out = RegexGuard.replaceTimeout("a1b2c3", "\\d", "#", onlyFirst = true)
        assertEquals("a#b2c3", out)
    }

    @Test
    fun `guard expands group refs`() {
        RegexGuard.reset()
        val out = RegexGuard.replaceTimeout(
            "作者：张三/2021",
            "^作者：([^/]+)/.*$",
            "$1",
            onlyFirst = false
        )
        assertEquals("张三", out)
    }

    @Test
    fun `analysis surfaces RegexTimeout error`() {
        RegexGuard.reset()
        RegexGuard.timeoutMs = 300
        val payload = "a".repeat(100)
        val a = AnalyzeRule()
        a.setContent("<p>$payload</p>", "https://x/")
        val v = a.getString("p@text##(.*a){30}##x")
        assertEquals("超时后应保留原文", payload, v)
        assertTrue("应记 RegexTimeout，实际=${a.lastError}", a.lastError is RuleError.RegexTimeout)
        RegexGuard.timeoutMs = 800
    }
}
