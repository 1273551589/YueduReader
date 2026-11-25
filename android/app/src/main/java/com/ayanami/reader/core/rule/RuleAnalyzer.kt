package com.ayanami.reader.core.rule

/**
 * 规则串切分器。
 *
 * 负责把一条规则按 `&&` / `||` 拆成若干段，且 **不能** 误切嵌在
 * `##正则##`、`@js:`、`{{...}}`、引号、括号里的运算符。
 *
 * 对应 Legado 的 `RuleAnalyzer`。这里只实现首版需要的子集，
 * 行为在 [RuleAnalyzerTest] 里用固定用例锁住。
 */
object RuleAnalyzer {

    enum class Mode { Default, Regex, Js, Json }

    data class RuleSplit(
        val rules: List<String>,
        /** "&&" 或 "||" */
        val mode: String
    )

    private val OPERATORS = listOf("&&", "||", "%%")

    /** 按 `&&` / `||` 拆分，保留顺序。 */
    fun splitRule(ruleStr: String): RuleSplit {
        val parts = ArrayList<String>()
        val ops = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var bracket = 0
        var inSingle = false
        var inDouble = false
        var regMode = false
        var jsMode = false

        while (i < ruleStr.length) {
            val c = ruleStr[i]

            if (c == '\'' && !inDouble && !regMode && !jsMode) inSingle = !inSingle
            if (c == '"' && !inSingle && !regMode && !jsMode) inDouble = !inDouble

            if (!inSingle && !inDouble) {
                // ## 正则分界的切换
                if (c == '#' && i + 1 < ruleStr.length && ruleStr[i + 1] == '#') {
                    regMode = !regMode
                    sb.append("##")
                    i += 2
                    continue
                }
                // @js: / <js> 进入脚本模式后不再拆 && ||
                if (!regMode && (c == '@' && ruleStr.startsWith("@js:", i))) {
                    jsMode = true
                }
                if (!regMode && !jsMode) {
                    if (c == '<' && ruleStr.startsWith("<js>", i)) {
                        jsMode = true
                    }
                    if (c == '(' || c == '[' || c == '{') bracket++
                    if (c == ')' || c == ']' || c == '}') bracket--
                }
            }

            if (!inSingle && !inDouble && !regMode && !jsMode && bracket <= 0) {
                val op = OPERATORS.firstOrNull { op ->
                    ruleStr.startsWith(op, i) && op !== "%%"
                }
                if (op != null) {
                    parts.add(sb.toString())
                    ops.add(op)
                    sb.setLength(0)
                    i += op.length
                    continue
                }
            }

            sb.append(c)
            i++
        }
        parts.add(sb.toString())

        // 只有一种运算符时才是有意义的组合；混用按出现顺序退化处理
        val distinct = ops.distinct()
        val mode = if (distinct.size == 1) distinct.first() else "&&"
        return RuleSplit(parts, mode)
    }

    /**
     * 拆出 `##正则##替换` 形式。
     *
     * Legado 的语义就是 `ruleStr.split("##")`：
     * - `a##b`      → 正则 b，替换为空串
     * - `a##b##c`   → 正则 b，替换 c
     * - `a##b##c###`→ 同上，且只替换第一个
     *
     * 所以按 `##` 直接切，不要用 indexOf 拼。
     */
    fun splitRegex(ruleStr: String): RegexSplit? {
        if (!ruleStr.contains("##")) return null

        // 结尾的 `###` 是「只替换首个」，先剥掉，否则 split 会把替换词切成 "c#"
        var s = ruleStr
        var onlyFirst = false
        if (s.endsWith("###")) {
            onlyFirst = true
            s = s.dropLast(1)      // "a##b##c##" —— 留下正常的双井号
        }

        val parts = s.split("##")
        if (parts.size < 2) return null

        val body = parts[0]
        val pattern = parts[1]
        if (pattern.isEmpty()) return null

        val replacement = if (parts.size > 2) parts[2] else ""
        return RegexSplit(body, pattern, replacement, onlyFirst)
    }

    data class RegexSplit(
        val body: String,
        val pattern: String,
        val replacement: String,
        val onlyFirst: Boolean
    )

    /**
     * 拆出 `@css:` / `@XPath:` / `@Json:` / `@js:` 前缀，以及 `@put:` / `@get:` 指令。
     */
    fun parsePrefix(ruleStr: String): ParsedRule {
        var s = ruleStr.trim()
        var type = RuleType.Default
        var raw = false

        if (s.startsWith("@@")) {
            raw = true
            s = s.substring(2)
        }

        // @put:{...} @get:{...} 可以出现在任意位置
        val putMap = LinkedHashMap<String, String>()
        val getKeys = ArrayList<String>()
        s = extractDirective(s, "@put:", putMap)
        s = extractDirective(s, "@get:", null, getKeys)

        when {
            s.startsWith("@css:", ignoreCase = true) -> {
                type = RuleType.Css; s = s.substring(5)
            }
            s.startsWith("@xpath:", ignoreCase = true) -> {
                type = RuleType.XPath; s = s.substring(7)
            }
            s.startsWith("@json:", ignoreCase = true) -> {
                type = RuleType.Json; s = s.substring(6)
            }
            s.startsWith("@js:", ignoreCase = true) -> {
                type = RuleType.Js; s = s.substring(4)
            }
            s.startsWith("<js>") -> {
                type = RuleType.Js
                s = s.removePrefix("<js>").removeSuffix("</js>")
            }
        }

        // 裸 JSON 自动判 JSON
        if (type == RuleType.Default && !raw) {
            val t = s.trimStart()
            if (t.startsWith("$.") || t.startsWith("$[") || t.startsWith("$.")) {
                type = RuleType.Json
            }
        }

        return ParsedRule(s.trim(), type, raw, putMap, getKeys)
    }

    private fun extractDirective(
        s: String,
        tag: String,
        target: MutableMap<String, String>? = null,
        keys: MutableList<String>? = null
    ): String {
        var result = s
        while (true) {
            val idx = result.indexOf(tag)
            if (idx < 0) break
            val start = idx + tag.length
            if (start >= result.length || result[start] != '{') break
            var depth = 0
            var end = -1
            var j = start
            while (j < result.length) {
                when (result[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = j; break }
                    }
                }
                j++
            }
            if (end < 0) break
            // 保留花括号：parseStringMap 要的是完整 JSON 对象
            val body = result.substring(start, end + 1)
            if (target != null) parsePutBody(body, target)
            if (keys != null) parseGetBody(body, keys)
            result = result.substring(0, idx) + result.substring(end + 1)
        }
        return result
    }

    /** `@put:{"key":"rule", ...}` —— 值是规则串，用宽松 JSON 解析。 */
    private fun parsePutBody(body: String, target: MutableMap<String, String>) {
        LenientJson.parseStringMap(body)?.forEach { (k, v) -> target[k] = v }
    }

    /** `@get:{"key":"value", "key2":null}` —— 只需 key。 */
    private fun parseGetBody(body: String, keys: MutableList<String>) {
        LenientJson.parseStringMap(body)?.keys?.forEach { keys.add(it) }
    }
}

enum class RuleType { Default, Css, XPath, Json, Js }

data class ParsedRule(
    val rule: String,
    val type: RuleType,
    val raw: Boolean,
    val puts: Map<String, String>,
    val gets: List<String>
)
