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
 *
 * ### v0.4.0 重写要点
 *
 * 1. **默认选择器语法**（`class.` / `id.` / `tag.` / `text.` / `children`）
 *    交给 [RuleSteps] 求值，不再直接丢给 `jsoup.select()`。
 * 2. **`@` 链式逐级下钻** —— 按 `@` 切成 N 段依次过滤，只有末段是取值符
 *    （`text` / `html` / `href` / `src` / `ownText` …）时才做字符串提取。
 *    旧实现 `indexOf('@')` 只认第一段，`class.odd.0@tag.a@href` 必然为空。
 * 3. **后缀脚本 `<js>`** —— `规则主体<js>脚本</js>`，前段结果作为 `result` 注入。
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

    private val ruleCache = HashMap<String, ParsedRule>()
    private val stepCache = HashMap<String, RuleSteps.Spec>()

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
        } catch (e: RuleSteps.BadSelectorException) {
            lastError = RuleError.RuleSyntaxError(e.selector).also { onRuleError?.invoke(it) }
            null
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
        } catch (e: RuleSteps.BadSelectorException) {
            lastError = RuleError.RuleSyntaxError(e.selector).also { onRuleError?.invoke(it) }
            null
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
            RuleType.Css, RuleType.Default -> try {
                val steps = RuleSteps.splitSteps(parsed.rule)
                val sel = RuleSteps.selectorSteps(steps)
                selectSteps(sel.map { it.trim() })
            } catch (e: RuleSteps.BadSelectorException) {
                lastError = RuleError.RuleSyntaxError(e.selector)
                    .also { onRuleError?.invoke(it) }
                emptyList()
            }
            RuleType.XPath -> queryXPath(parsed.rule)
            RuleType.Json, RuleType.Js -> emptyList()
        }
    }

    // ---------------- 求值 ----------------

    private fun parse(ruleStr: String): ParsedRule =
        ruleCache.getOrPut(ruleStr) { RuleAnalyzer.parsePrefix(ruleStr) }

    private fun specOf(step: String): RuleSteps.Spec =
        stepCache.getOrPut(step) { RuleSteps.parse(step) }

    private fun evalString(ruleStr: String): String {
        val trimmed = ruleStr.trim()
        if (trimmed.isEmpty()) return ""

        // 0) 后缀脚本 `主体<js>脚本</js>` —— 前段结果注入 result
        splitSuffixJs(trimmed)?.let { (body, script) ->
            val base = if (body.isBlank()) "" else evalString(body)
            return runJs(script, initialResult = base)
        }

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
     * 拆 `规则主体<js>脚本</js>`。
     *
     * 只认**末尾**那一段 —— 开头就是 `<js>` 的走 [RuleAnalyzer.parsePrefix] 的老路径。
     */
    private fun splitSuffixJs(rule: String): Pair<String, String>? {
        val open = rule.indexOf("<js>", ignoreCase = true)
        if (open <= 0) return null
        val close = rule.lastIndexOf("</js>", ignoreCase = true)
        if (close < open) return null
        val body = rule.substring(0, open)
        val script = rule.substring(open + 4, close)
        return body to script
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
            RuleType.Css, RuleType.Default -> cssValue(rule, parsed.rule)
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
     * CSS 链求值。
     *
     * @param rule       插值后的规则（`{{key}}` 已替换）
     * @param unparsed   原始规则 —— 插值可能把变量插进选择器里，导致
     *                   末段判断不同；这里以插值后的为准，unparsed 只在
     *                   插值后为空时兜底。
     */
    private fun cssValue(rule: String, unparsed: String): String {
        val effective = if (rule.isNotBlank()) rule else unparsed
        val steps = RuleSteps.splitSteps(effective).map { it.trim() }
        if (steps.isEmpty()) return ""

        // 末段是取值符 → 前 N-1 段选择器
        val extractor = RuleSteps.extractorOf(steps)
        val selectorSteps = if (extractor != null) steps.dropLast(1) else steps
        val attr = extractor ?: "text"

        val selected = selectSteps(selectorSteps)

        // 末段不是已知取值符时，允许"退一步当属性名"：
        // class.book.0@title 这种（title 既是标签名也是属性）
        if (extractor == null && selectorSteps.size > 1) {
            val last = selectorSteps.last()
            if (isPlainWord(last) && selected.isEmpty()) {
                val parent = selectSteps(selectorSteps.dropLast(1))
                val el = parent.firstOrNull()
                if (el != null && el.hasAttr(last)) return extractAttr(el, last)
            }
        }

        for (el in selected) {
            val v = extractAttr(el, attr)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun isPlainWord(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' }

    /** 依次应用选择器链，返回最终元素集合。 */
    private fun selectSteps(steps: List<String>): List<Element> {
        val roots = when {
            element != null -> listOf(element!!)
            document != null -> listOf(document!!)
            else -> emptyList()
        }
        if (roots.isEmpty()) return emptyList()

        // 全空选择器链（`@text` 这种）→ 作用在根节点
        if (steps.isEmpty() || steps.all { it.isBlank() }) return roots

        var current: List<Element> = roots
        for (raw in steps) {
            if (raw.isBlank()) continue
            val spec = specOf(raw)
            current = applySpec(spec, current)
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private fun applySpec(spec: RuleSteps.Spec, input: List<Element>): List<Element> {
        val base = when (spec.accessor) {
            "children" -> input.flatMap { it.children() }
            "class" -> Elements2.byClass(input, spec.arg)
            "id" -> Elements2.byId(input, spec.arg)
            "tag" -> Elements2.byTag(input, spec.arg)
            "text" -> Elements2.byText(input, spec.arg)
            else -> Elements2.byCss(input, spec.arg)
        }
        return Elements2.applyFilters(spec, base)
    }

    /**
     * 执行 `@js` 规则。
     *
     * `result` 变量是 Legado 的约定：脚本里写 `result = "xxx"` 就是返回值。
     * 没赋值时取脚本最后一个表达式的结果。
     */
    private fun runJs(script: String, initialResult: String? = null): String {
        val rt = jsRuntime
        if (rt == null) {
            lastError = RuleError.UnsupportedApi("@js（当前上下文没有 JS 运行时）")
                .also { onRuleError?.invoke(it) }
            return ""
        }

        // 把当前内容与已有变量喂进去
        val payload: Any? = element ?: document ?: json
        rt.bindVar("baseUrl", baseUrl)
        rt.bindVar("result", initialResult ?: "")

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

    // ---------------- 属性提取 ----------------

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
                        if (name == "href" || name == "src" || name == "data-src" ||
                            name == "data-original" || name == "poster"
                        ) absoluteUrl(raw) else raw
                    } else ""
                }
            }
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun absoluteUrl(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("data:") || raw.startsWith("javascript:")) return raw
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
        // `&&` 组合在列表上不常见，但 `##` 常见 —— 逐项应用正则
        val regexSplit = RuleAnalyzer.splitRegex(ruleStr)
        if (regexSplit != null) {
            val base = evalStringList(regexSplit.body.trim())
            return base.mapNotNull { item ->
                applyRegex(item, regexSplit).takeIf { it.isNotBlank() }
            }
        }

        val parsed = parse(ruleStr)
        return when (parsed.type) {
            RuleType.Json -> {
                val single = jsonFirstOrNull(parsed.rule)
                if (single.isNullOrEmpty()) emptyList() else single.split("\n")
            }
            RuleType.Js -> {
                val v = runJs(parsed.rule)
                if (v.isBlank()) emptyList() else v.split("\n")
            }
            RuleType.XPath -> {
                val atIdx = parsed.rule.lastIndexOf('@')
                val expr = if (atIdx > 0) parsed.rule.substring(0, atIdx) else parsed.rule
                val attr = if (atIdx > 0) parsed.rule.substring(atIdx + 1) else "text"
                queryXPath(expr).mapNotNull { el ->
                    extractAttr(el, attr).takeIf { it.isNotBlank() }
                }
            }
            else -> {
                val steps = RuleSteps.splitSteps(interpolate(parsed.rule)).map { it.trim() }
                if (steps.isEmpty()) return emptyList()
                val extractor = RuleSteps.extractorOf(steps)
                val selectorSteps = if (extractor != null) steps.dropLast(1) else steps
                val attr = extractor ?: "text"
                selectSteps(selectorSteps).mapNotNull { el ->
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

/**
 * 元素集合的取法 —— 从 [RuleSteps] 抽出来，让单测能直接打。
 *
 * 与 [RuleSteps] 的分工：那边负责**语法**（怎么切、怎么解析索引），
 * 这里负责**求值**（怎么在 jsoup 上取）。
 */
internal object Elements2 {

    fun byCss(input: List<Element>, selector: String): List<Element> {
        val sel = selector.trim()
        if (sel.isEmpty()) return input
        if (sel == "*") return input.flatMap { it.getAllElements() }.distinct()

        // `> a` 表示直接子元素，jsoup 的 select 不支持前导 >
        if (sel.startsWith(">")) {
            val rest = sel.substring(1).trim()
            if (rest.isEmpty()) return input.flatMap { it.children() }
            return input.flatMap { parent ->
                parent.children().filter { child ->
                    runCatching { child.`is`(rest) }.getOrDefault(false)
                }
            }
        }

        for (root in input) {
            val direct = runCatching { root.select(sel).toList() }.getOrNull()
            if (direct != null) return direct
        }
        // 全部 root 都抛异常 → 选择器非法，交给上层报错
        throw RuleSteps.BadSelectorException(sel, "选择器非法")
    }

    /**
     * `class.x` —— Legado 的语义是 **`[class*=x]`（子串包含）**，不是精确类名。
     *
     * 这个区别很实际：书源常写 `class.book` 去命中 `class="book-item"`。
     * 按精确类名查会漏掉一大片，而书源作者在 Legado 里是能用的。
     */
    fun byClass(input: List<Element>, name: String): List<Element> {
        if (name.isBlank()) return emptyList()
        val out = ArrayList<Element>()
        for (root in input) {
            val hit = runCatching {
                root.select("[class*=${cssQuoted(name)}]").toList()
            }.getOrElse { emptyList() }
            if (hit.isNotEmpty()) out.addAll(hit)
            else out.addAll(root.select("*").filter { it.className().contains(name) })
        }
        return out.distinct()
    }

    fun byId(input: List<Element>, name: String): List<Element> {
        if (name.isBlank()) return emptyList()
        val out = ArrayList<Element>()
        for (root in input) {
            val hit = runCatching {
                root.select("[id=${cssQuoted(name)}]").toList()
            }.getOrElse { emptyList() }
            if (hit.isNotEmpty()) out.addAll(hit)
            else out.addAll(root.select("*").filter { it.id() == name })
        }
        return out.distinct()
    }

    fun byTag(input: List<Element>, name: String): List<Element> {
        val tag = name.trim()
        if (tag.isEmpty()) return emptyList()
        val out = ArrayList<Element>()
        for (root in input) {
            if (tag.matches(Regex("[A-Za-z][A-Za-z0-9-]*"))) {
                out.addAll(root.select(tag))
            } else {
                out.addAll(root.getAllElements().filter { it.tagName() == tag })
            }
        }
        return out.distinct()
    }

    /**
     * `text.关键字`：先按 ownText 精确找，找不到退一步按全文找。
     *
     * 为什么要退：很多站把文字包在子 `<span>` 里，父节点的 ownText 是空的，
     * 但书源作者看的正是浏览器里显示的整段文字。
     */
    fun byText(input: List<Element>, needle: String): List<Element> {
        if (needle.isBlank()) return emptyList()
        val out = ArrayList<Element>()
        for (root in input) {
            out.addAll(root.select("*").filter { it.ownText().contains(needle) })
        }
        if (out.isNotEmpty()) return out.distinct()
        for (root in input) {
            out.addAll(root.select("*").filter { it.text().contains(needle) })
        }
        return out.distinct()
    }

    fun applyFilters(spec: RuleSteps.Spec, list: List<Element>): List<Element> {
        if (spec.filters.isEmpty()) return list
        var out: List<Element> = list
        for (f in spec.filters) {
            if (out.isEmpty()) return out
            out = when (f) {
                is RuleSteps.Filter.Pick -> {
                    val idx = resolve(f.index, out.size)
                    out.getOrNull(idx)?.let { listOf(it) } ?: emptyList()
                }
                is RuleSteps.Filter.Exclude -> {
                    val idx = resolve(f.index, out.size)
                    out.filterIndexed { i, _ -> i != idx }
                }
                is RuleSteps.Filter.Range -> {
                    val from = resolve(f.from.coerceAtLeast(0), out.size)
                    val to = if (f.to < 0) out.size - 1 else f.to
                    if (from > to || from >= out.size) emptyList()
                    else out.subList(from, (to + 1).coerceAtMost(out.size)).toList()
                }
                is RuleSteps.Filter.ExcludeRange -> {
                    val from = resolve(f.from.coerceAtLeast(0), out.size)
                    val to = if (f.to < 0) out.size - 1 else f.to
                    out.filterIndexed { i, _ -> i < from || i > to }
                }
            }
        }
        return out
    }

    private fun resolve(index: Int, size: Int): Int =
        if (index < 0) size + index else index

    /** jsoup 的 CSS 转义：`.1a` / `a.b` 这种名字必须转义，否则抛异常。 */
    private fun cssEscape(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' -> sb.append(c)
                else -> sb.append('\\').append(c)
            }
        }
        if (name.isNotEmpty() && name[0].isDigit()) sb.insert(0, '\\')
        return sb.toString()
    }

    /**
     * 属性选择器里的字符串字面量。
     *
     * jsoup 要求**带引号**才认 `[class*="foo"]`，裸写的 `[class*=foo]`
     * 在名字含 `-` / `.` 时会解析失败。所以统一加双引号并转义内部引号。
     */
    private fun cssQuoted(name: String): String {
        val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
