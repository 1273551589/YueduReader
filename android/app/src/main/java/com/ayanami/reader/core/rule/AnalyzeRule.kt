package com.ayanami.reader.core.rule

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 有状态的规则解析器 —— 对应 Legado 的 `AnalyzeRule`。
 *
 * ⚠️ **实例状态必须按「阶段」隔离，不能跨阶段复用**（方案 §4.5）：
 * - `isRegex` 一旦为 true 就再不复位（上游缺陷），会污染后续所有 `getString`
 * - 三个规则缓存是实例字段
 *
 * 所以上层 `SourceSession` 在 搜索 / 详情 / 目录 / 正文 四个阶段各 new 一个实例。
 */
class AnalyzeRule {

    private var document: Document? = null
    private var element: Element? = null
    private var json: JsonElement? = null

    var baseUrl: String = ""

    /** `@put` 写入的变量表。 */
    private val putMap = LinkedHashMap<String, String>()

    /** 跨阶段共享变量（由 SourceSession 注入）。 */
    private var sharedVars: MutableMap<String, String>? = null

    /** `@js` 运行时。为 null 时命中脚本规则会报 UnsupportedApi。 */
    var jsRuntime: JsRuntime? = null

    /**
     * 规则出错的即时回调。
     *
     * 为什么需要它：`lastError` 每次 `getString` 开头都会被清空，
     * 而一个阶段里要跑很多次取值（26 条搜索结果 × 9 个字段）。
     * 最后一次成功就等于 `lastError == null`，
     * 前面那些"正则写错了"的痕迹全被冲掉。
     *
     * 所以必须**在出错当下**通知出去，不能事后回看。
     */
    var onRuleError: ((RuleError) -> Unit)? = null

    /** 上游缺陷复刻：只有 true，没有复位。 */
    private var isRegex = false

    private val ruleCache = HashMap<String, ParsedRule>()

    var lastError: RuleError? = null

    fun bindSharedVars(vars: MutableMap<String, String>) {
        sharedVars = vars
    }

    fun setContent(content: String, baseUrl: String = ""): AnalyzeRule {
        this.baseUrl = baseUrl
        val trimmed = content.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val el = LenientJson.parseElement(content)
            if (el != null) {
                json = el
                document = null
                element = null
                return this
            }
        }
        document = Jsoup.parse(content, baseUrl)
        element = document
        json = null
        return this
    }

    fun setContent(element: Element, baseUrl: String = ""): AnalyzeRule {
        this.baseUrl = baseUrl
        this.element = element
        this.document = element.ownerDocument()
        this.json = null
        return this
    }

    fun setContent(json: JsonElement, baseUrl: String = ""): AnalyzeRule {
        this.baseUrl = baseUrl
        this.json = json
        this.document = null
        this.element = null
        return this
    }

    /** @return 命中文本；规则跑通但为空返回 ""，规则本身出错返回 null。 */
    fun getString(ruleStr: String?): String? {
        if (ruleStr == null) return null
        if (ruleStr.isEmpty()) return ""
        lastError = null
        return try {
            evalString(ruleStr)
        } catch (e: Exception) {
            lastError = RuleError.RuleSyntaxError(ruleStr).also { onRuleError?.invoke(it) }
            null
        }
    }

    fun getStringList(ruleStr: String?): List<String>? {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        lastError = null
        return try {
            evalStringList(ruleStr)
        } catch (e: Exception) {
            lastError = RuleError.RuleSyntaxError(ruleStr).also { onRuleError?.invoke(it) }
            null
        }
    }

    fun getElement(ruleStr: String?): Element? = getElements(ruleStr).firstOrNull()

    fun getElements(ruleStr: String?): List<Element> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val parsed = parse(ruleStr)
        return when (parsed.type) {
            RuleType.Css, RuleType.Default -> queryCss(parsed.rule)
            RuleType.XPath -> queryXPath(parsed.rule)
            RuleType.Json, RuleType.Js -> emptyList()
        }
    }

    // ---------------- 求值 ----------------

    private fun parse(ruleStr: String): ParsedRule =
        ruleCache.getOrPut(ruleStr) { RuleAnalyzer.parsePrefix(ruleStr) }

    private fun evalString(ruleStr: String): String {
        val trimmed = ruleStr.trim()
        if (trimmed.isEmpty()) return ""

        // 1) `&&` / `||` 组合
        val split = RuleAnalyzer.splitRule(trimmed)
        if (split.rules.size > 1) {
            val results = split.rules.map { evalString(it.trim()) }
            return if (split.mode == "||") {
                results.firstOrNull { it.isNotEmpty() } ?: ""
            } else {
                results.filter { it.isNotEmpty() }.joinToString("\n")
            }
        }

        // 2) `##正则##替换`
        val regexSplit = RuleAnalyzer.splitRegex(trimmed)
        if (regexSplit != null) {
            val body = evalString(regexSplit.body.trim())
            if (body.isEmpty()) return ""
            return applyRegex(body, regexSplit)
        }

        return evalSingle(trimmed)
    }

    /**
     * 正则替换走 [RegexGuard] —— 灾难性回溯会卡死线程，必须带超时。
     * 超时时保留原文并记 `RegexTimeout`，让上层知道"这条规则没跑完"。
     */
    private fun applyRegex(input: String, split: RuleAnalyzer.RegexSplit): String {
        val guarded = RegexGuard.replaceTimeout(
            input = input,
            pattern = split.pattern,
            replacement = split.replacement,
            onlyFirst = split.onlyFirst
        )
        if (guarded == null) {
            lastError = RuleError.RegexTimeout(split.pattern)
                .also { onRuleError?.invoke(it) }
            return input
        }
        // 正则编译失败 → 显式报错（否则和"没匹配上"无法区分）
        RegexGuard.consumeCompileError()?.let { _ ->
            lastError = RuleError.RuleSyntaxError(split.pattern)
                .also { onRuleError?.invoke(it) }
        }
        return guarded
    }

    private fun evalSingle(ruleStrRaw: String): String {
        val parsed = parse(ruleStrRaw)
        val rule = interpolate(parsed.rule)

        val value: String = when (parsed.type) {
            RuleType.Css, RuleType.Default -> cssFirst(rule)
            RuleType.Json -> jsonFirst(rule)
            RuleType.XPath -> xpathFirst(rule)
            RuleType.Js -> runJs(parsed.rule)
        }

        if (parsed.raw) return value

        parsed.puts.forEach { (k, v) ->
            val resolved = evalString(v)
            putMap[k] = resolved
            sharedVars?.put(k, resolved)
        }

        if (parsed.gets.isNotEmpty()) {
            val parts = parsed.gets.mapNotNull { key -> readVar(key) }
            if (parts.isNotEmpty()) return parts.joinToString("")
        }

        return value
    }

    /**
     * 执行 `@js` 规则。
     *
     * `result` 变量是 Legado 的约定：脚本里写 `result = "xxx"` 就是返回值。
     * 没赋值时取脚本最后一个表达式的结果。
     */
    private fun runJs(script: String): String {
        val rt = jsRuntime
        if (rt == null) {
            lastError = RuleError.UnsupportedApi("@js（当前上下文没有 JS 运行时）")
                .also { onRuleError?.invoke(it) }
            return ""
        }

        // 把当前内容与已有变量喂进去
        val payload: Any? = element ?: document ?: json
        rt.bindVar("baseUrl", baseUrl)
        rt.bindVar("result", "")

        val out = rt.eval(script, payload, baseUrl)

        // 脚本里显式 result = 的优先
        val explicit = rt.getVar("result")
        if (explicit != null && explicit.toString().isNotEmpty()) {
            return explicit.toString()
        }

        val rtError = rt.lastError
        if (rtError != null) {
            lastError = rtError
            onRuleError?.invoke(rtError)
            return ""
        }
        val unsupported = rt.lastUnsupported
        if (unsupported != null) {
            lastError = RuleError.UnsupportedApi(unsupported)
        }
        return out ?: ""
    }

    private fun readVar(key: String): String? = putMap[key] ?: sharedVars?.get(key)

    /** `{{key}}` → 变量值；`{{$.path}}` → 当前 JSON 取值。 */
    private fun interpolate(input: String): String {
        if (!input.contains("{{")) return input
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input.startsWith("{{", i)) {
                val end = input.indexOf("}}", i + 2)
                if (end > 0) {
                    val key = input.substring(i + 2, end).trim()
                    var v = readVar(key)
                    if (v == null && key.startsWith("$")) v = jsonFirstOrNull(key)
                    sb.append(v ?: "")
                    i = end + 2
                    continue
                }
            }
            sb.append(input[i]); i++
        }
        return sb.toString()
    }

    // ---------------- CSS ----------------

    private fun cssFirst(ruleRaw: String): String {
        val atIdx = findAttrSeparator(ruleRaw)
        val selectorPart = if (atIdx >= 0) ruleRaw.substring(0, atIdx) else ruleRaw
        val attr = if (atIdx >= 0) ruleRaw.substring(atIdx + 1) else "text"

        var selector = selectorPart.trim()

        // 纯 `@text` / `@href` —— 没有选择器，直接作用在根节点
        if (selector.isEmpty()) {
            val root = element ?: document
            return root?.let { extractAttr(it, attr) } ?: ""
        }

        var explicitIndex = -1

        // `div.0` 这类明确下标（注意不要和 class 选择器混淆：只在结尾全是数字时认）
        val dotIdx = selector.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < selector.length - 1) {
            val tail = selector.substring(dotIdx + 1)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                explicitIndex = tail.toInt()
                selector = selector.substring(0, dotIdx)
            }
        }

        for (cand in selector.split("!")) {
            val c = cand.trim()
            if (c.isEmpty()) continue
            val els = runCatching { queryCss(c) }.getOrDefault(emptyList())
            val target = if (explicitIndex in els.indices) els[explicitIndex] else els.firstOrNull()
            val v = target?.let { extractAttr(it, attr) } ?: ""
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun queryCss(selectorRaw: String): List<Element> {
        var sel = selectorRaw.trim()
        if (sel.isEmpty()) return emptyList()

        val root = element
        val doc = document

        // 空选择器 / `@text` 这种"直接取根节点"的写法
        if (sel.isEmpty() || sel.startsWith("@")) {
            // `@text` 已在 findAttrSeparator 之前处理不到，这里兜底返回根
            val r = root ?: doc
            return listOfNotNull(r)
        }

        if (sel.startsWith(">")) {
            sel = sel.substring(1).trim()
            if (sel.isEmpty()) return listOfNotNull(root)
            return root?.select(sel)?.toList() ?: emptyList()
        }
        if (sel == "*") {
            return listOfNotNull(root ?: doc)
        }
        // 相对根节点优先（搜索结果条目里的规则要落在当前条目内）
        if (root != null) {
            val direct = runCatching { root.select(sel) }.getOrNull()
            if (direct != null && direct.isNotEmpty()) return direct.toList()
        }
        return runCatching { doc?.select(sel)?.toList() }.getOrNull() ?: emptyList()
    }

    private fun extractAttr(el: Element, attrRaw: String): String {
        val attrs = attrRaw.split("@").filter { it.isNotBlank() }
        if (attrs.isEmpty()) return el.text()

        for (a in attrs) {
            val name = a.trim()
            val v: String = when (name) {
                "text", "textNodes" -> el.text()
                "ownText" -> el.ownText()
                "html" -> el.html()
                "outerHtml", "all" -> el.outerHtml()
                "abs:href" -> absoluteUrl(el.attr("href"))
                "abs:src" -> absoluteUrl(el.attr("src"))
                else -> {
                    if (el.hasAttr(name)) {
                        val raw = el.attr(name)
                        if (name == "href" || name == "src") absoluteUrl(raw) else raw
                    } else ""
                }
            }
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun findAttrSeparator(rule: String): Int {
        var bracket = 0
        var quote: Char? = null
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            if (quote != null) {
                if (c == quote) quote = null
                i++
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '(', '[' -> bracket++
                ')', ']' -> bracket--
                '@' -> if (bracket == 0) return i
            }
            i++
        }
        return -1
    }

    private fun absoluteUrl(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return UrlHelper.resolve(baseUrl, raw)
    }

    // ---------------- JSON ----------------

    private fun jsonFirst(rule: String): String = jsonFirstOrNull(rule) ?: ""

    private fun jsonFirstOrNull(ruleRaw: String): String? {
        val rootJson = json ?: return null
        var rule = ruleRaw.trim()
        if (rule.isEmpty()) return null
        if (!rule.startsWith("$")) rule = "$.$rule"
        if (rule == "$") return rootJson.asTextSafe()

        val segments = parseJsonPath(rule)
        var current: JsonElement? = rootJson

        for (seg in segments) {
            val cur = current ?: return null
            current = when (seg) {
                is JsonSeg.Key -> (cur as? JsonObject)?.get(seg.name)
                is JsonSeg.Index -> (cur as? JsonArray)?.let { arr ->
                    if (seg.index in 0 until arr.size()) arr.get(seg.index) else null
                }
                is JsonSeg.Wildcard -> (cur as? JsonArray)?.let { arr ->
                    // 通配取全量时直接返回拼接结果
                    return arr.joinToString("\n") { it.asTextSafe() }
                }
            }
        }
        return current?.asTextSafe()
    }

    private sealed interface JsonSeg {
        data class Key(val name: String) : JsonSeg
        data class Index(val index: Int) : JsonSeg
        data object Wildcard : JsonSeg
    }

    private fun parseJsonPath(rule: String): List<JsonSeg> {
        val out = ArrayList<JsonSeg>()
        val sb = StringBuilder()
        var i = 1 // skip '$'

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(JsonSeg.Key(sb.toString()))
                sb.setLength(0)
            }
        }

        while (i < rule.length) {
            when (rule[i]) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    val end = rule.indexOf(']', i)
                    if (end < 0) return out
                    val inner = rule.substring(i + 1, end).trim()
                    when {
                        inner == "*" -> out.add(JsonSeg.Wildcard)
                        inner.toIntOrNull() != null -> out.add(JsonSeg.Index(inner.toInt()))
                        else -> out.add(JsonSeg.Key(inner.trim('\'', '"')))
                    }
                    i = end + 1
                }
                else -> { sb.append(rule[i]); i++ }
            }
        }
        flush()
        return out
    }

    // ---------------- XPath ----------------

    private fun xpathFirst(rule: String): String {
        val atIdx = rule.lastIndexOf('@')
        val expr = if (atIdx > 0) rule.substring(0, atIdx) else rule
        val attr = if (atIdx > 0) rule.substring(atIdx + 1) else "text"
        val target = queryXPath(expr).firstOrNull() ?: return ""
        return extractAttr(target, attr)
    }

    private fun queryXPath(expr: String): List<Element> {
        val doc = document ?: element?.ownerDocument() ?: return emptyList()
        return runCatching { doc.selectXpath(expr).toList() }.getOrElse { emptyList() }
    }

    // ---------------- 列表 ----------------

    private fun evalStringList(ruleStr: String): List<String> {
        val parsed = parse(ruleStr)
        return when (parsed.type) {
            RuleType.Json -> {
                val single = jsonFirstOrNull(parsed.rule)
                if (single.isNullOrEmpty()) emptyList() else single.split("\n")
            }
            RuleType.Js -> {
                lastError = RuleError.UnsupportedApi("@js 规则（首版不支持脚本）")
                emptyList()
            }
            else -> {
                val atIdx = findAttrSeparator(parsed.rule)
                val selector = if (atIdx >= 0) parsed.rule.substring(0, atIdx) else parsed.rule
                val attr = if (atIdx >= 0) parsed.rule.substring(atIdx + 1) else "text"
                queryCss(selector.trim()).mapNotNull { el ->
                    extractAttr(el, attr).takeIf { it.isNotBlank() }
                }
            }
        }
    }

    fun dumpVars(): Map<String, String> = putMap.toMap()
}

fun JsonElement.asTextSafe(): String = when (this) {
    is JsonObject -> toString()
    is JsonArray -> toString()
    else -> if (isJsonNull) "" else asString
}
