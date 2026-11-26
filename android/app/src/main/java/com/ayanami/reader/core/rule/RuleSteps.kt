package com.ayanami.reader.core.rule

/**
 * Legado **默认选择器语法** 的切分与解析（只负责语法，不做 DOM 求值）。
 *
 * 这是 v0.4.0 最核心的一块。43 条实测书源搜不到内容，根因就是
 * `class.odd.0@tag.a@href` 这种写法被整条丢给 `jsoup.select()` →
 * 抛 `SelectorParseException` → 整条搜索规则静默返回空。
 *
 * ### 切分语义
 *
 * 一条 CSS 规则按 `@` 切成若干 **step**，逐级下钻：
 *
 * ```
 * class.odd.0@tag.a@href
 * └── step1 ──┘└ step2 ┘└step3┘   ← 末段是取值符，前面都是选择器
 * ```
 *
 * ### step 形态
 *
 * | 形态 | 含义 |
 * |---|---|
 * | `class.名字` | class 含该名的元素 |
 * | `id.名字` | id 等于该名的元素 |
 * | `tag.标签` | 该标签元素 |
 * | `text.文字` | 文字含该串的元素 |
 * | `children` | 上一段的直接子元素 |
 * | 其它 | 原样当 jsoup CSS 选择器 |
 *
 * ### 索引后缀
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `.0` `.2` | 取该下标（负数从末尾数：`.-1` 是最后一个） |
 * | `!0` | 排除该下标 |
 * | `[1,3]` / `1:3` | 闭区间 |
 * | `!0:2` | 排除区间 |
 *
 * 索引越界不报错、结果为空 —— 与 Legado 一致（书源作者常靠它探边界）。
 * 求值在 [Elements2] 里做，这里只管把字符串拆干净。
 */
object RuleSteps {

    /** 会被识别为默认语法的访问器名。 */
    private val ACCESSORS = setOf("class", "id", "tag", "text")

    /** 末段取值符白名单。 */
    private val EXTRACTORS = setOf(
        "text", "textNodes", "ownText", "html", "outerHtml", "all",
        "href", "src", "title", "alt", "content", "value", "id", "class",
        "style", "onclick", "action", "name", "rel", "datetime", "data"
    )

    /** `data-src` / `data-original` 这类自定义属性也算取值符。 */
    private fun isDataAttr(s: String): Boolean = s.startsWith("data-") || s.startsWith("abs:")

    // ---------------- 切分 ----------------

    /**
     * 按 `@` 逐级切开。
     *
     * 必须躲开这些区域里的 `@`：
     * - `##正则##`（邮箱正则 `[\w.]+@[\w.]+` 里就有 @）
     * - `{{变量}}` / `{{$.path}}`
     * - 引号 / 圆括号 / 方括号内部（`li[data-mail='a@b']`）
     *
     * 旧实现 `indexOf('@')` 只取第一段，`class.odd.0@tag.a@href` 被切成
     * `class.odd.0` + `tag.a@href`，第二段当完整规则再跑一次，结果恒空。
     */
    fun splitSteps(rule: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var bracket = 0
        var quote: Char? = null
        var inRegex = false
        var mustache = 0

        while (i < rule.length) {
            val c = rule[i]

            if (quote != null) {
                sb.append(c)
                if (c == quote) quote = null
                i++
                continue
            }

            // {{ ... }}
            if (!inRegex && c == '{' && i + 1 < rule.length && rule[i + 1] == '{') {
                mustache++
                sb.append("{{")
                i += 2
                continue
            }
            if (mustache > 0 && c == '}' && i + 1 < rule.length && rule[i + 1] == '}') {
                mustache--
                sb.append("}}")
                i += 2
                continue
            }

            // ## 正则分界
            if (mustache == 0 && c == '#' && i + 1 < rule.length && rule[i + 1] == '#') {
                inRegex = !inRegex
                sb.append("##")
                i += 2
                continue
            }

            if (!inRegex && mustache == 0) {
                when (c) {
                    '\'', '"' -> {
                        quote = c
                        sb.append(c)
                        i++
                        continue
                    }
                    '(', '[', '{' -> bracket++
                    ')', ']', '}' -> if (bracket > 0) bracket--
                    '@' -> if (bracket == 0) {
                        out.add(sb.toString())
                        sb.setLength(0)
                        i++
                        continue
                    }
                }
            }

            sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ---------------- 末段判断 ----------------

    fun isExtractor(step: String): Boolean {
        val s = step.trim()
        if (s.isEmpty()) return false
        return s in EXTRACTORS || isDataAttr(s)
    }

    /** `class.odd.0@tag.a@href` → `href` */
    fun extractorOf(steps: List<String>): String? =
        steps.lastOrNull()?.trim()?.takeIf { isExtractor(it) }

    /** 去掉末段取值符后的选择器链。 */
    fun selectorSteps(steps: List<String>): List<String> =
        if (steps.size > 1 && extractorOf(steps) != null) steps.dropLast(1) else steps

    // ---------------- 解析 ----------------

    data class Spec(
        /** class / id / tag / text / children / css */
        val accessor: String,
        val arg: String,
        val filters: List<Filter>,
        val raw: String
    )

    sealed interface Filter {
        data class Pick(val index: Int) : Filter
        data class Range(val from: Int, val to: Int) : Filter
        data class Exclude(val index: Int) : Filter
        data class ExcludeRange(val from: Int, val to: Int) : Filter
    }

    /**
     * 解析单个 step。
     *
     * ```
     * class.odd.0    → accessor=class, arg=odd,   filters=[Pick(0)]
     * tag.tr!0       → accessor=tag,   arg=tr,    filters=[Exclude(0)]
     * tag.div[1,3]   → accessor=tag,   arg=div,   filters=[Range(1,3)]
     * children.0     → accessor=children, arg="", filters=[Pick(0)]
     * div.list > a   → accessor=css,   arg 原样
     * span.text.0    → accessor=css,   arg=span.text, filters=[Pick(0)]
     * ```
     */
    fun parse(stepRaw: String): Spec {
        val step = stepRaw.trim()
        if (step.isEmpty()) return Spec("css", "", emptyList(), step)

        val dot = step.indexOf('.')
        val head = if (dot < 0) step else step.substring(0, dot)
        val lower = head.lowercase()

        // `children` 单独处理：它没有参数，可能带索引（children.0 / children!1）
        if (lower == "children") {
            val rest = if (dot >= 0) step.substring(dot + 1) else ""
            // 注意这里没有前导 `.` —— `children.1` 剥掉头之后 rest 就是光秃秃的 "1"，
            // 不能按「字面 arg」处理，否则索引丢掉（实测 `children.1` 取到第 0 个）
            val filters = parseFilterChain(rest, allowArg = false, bareTokenIsFilter = true).second
            return Spec("children", "", filters, step)
        }

        val isAccessor = dot >= 0 && lower in ACCESSORS
        if (!isAccessor) {
            val (sel, filters) = peelTrailingCssIndex(step)
            return Spec("css", sel, filters, step)
        }

        val rest = step.substring(dot + 1)
        val (arg, filters) = parseFilterChain(rest, allowArg = true)
        return Spec(lower, arg, filters, step)
    }

    /**
     * `span.text.0` → (`span.text`, [Pick(0)])
     *
     * 只剥**尾部**那一个 `.N`，且必须整段都是整数（可带负号）。
     * 为什么不循环剥：`div.x2.y` 里 `.y` 不是数字会停，`div.a.1.2` 多剥
     * 容易把 class 名吃掉。书源里最多出现一层。
     */
    private fun peelTrailingCssIndex(step: String): Pair<String, List<Filter>> {
        val idx = step.lastIndexOf('.')
        if (idx <= 0) return step to emptyList()
        val tail = step.substring(idx + 1)
        val n = tail.toIntOrNull() ?: return step to emptyList()
        return step.substring(0, idx) to listOf(Filter.Pick(n))
    }

    /**
     * 从 `odd.0` / `tr!0` / `div[1,3]` 里剥出 arg 与过滤器。
     *
     * @param allowArg true 时开头的字面部分是 arg（`class.odd` 的 `odd`）；
     *   false 时只认过滤器（`children.0`）。
     */
    private fun parseFilterChain(
        rest: String,
        allowArg: Boolean,
        bareTokenIsFilter: Boolean = false
    ): Pair<String, List<Filter>> {
        val arg = StringBuilder()
        val filters = ArrayList<Filter>()
        var i = 0

        fun readToken(start: Int): Int {
            var j = start
            var depth = 0
            while (j < rest.length) {
                val d = rest[j]
                if (d == '[') depth++
                if (d == ']' && depth > 0) depth--
                if (d == '.' && depth == 0) break
                j++
            }
            return j
        }

        // `children.1` → rest="1"，整个 rest 就是一个过滤器（没有前导点/叹号可依）
        if (bareTokenIsFilter) {
            return "" to parseFilterToken(rest, excluded = false)
        }

        while (i < rest.length) {
            when (val c = rest[i]) {
                '.' -> {
                    val j = readToken(i + 1)
                    filters.addAll(parseFilterToken(rest.substring(i + 1, j), excluded = false))
                    i = j
                }
                '!' -> {
                    val j = readToken(i + 1)
                    filters.addAll(parseFilterToken(rest.substring(i + 1, j), excluded = true))
                    i = j
                }
                '[' -> {
                    val end = rest.indexOf(']', i)
                    if (end < 0) {
                        if (allowArg) arg.append(c)
                        i++
                    } else {
                        filters.addAll(parseFilterToken(rest.substring(i + 1, end), excluded = false))
                        i = end + 1
                    }
                }
                else -> {
                    if (allowArg) arg.append(c)
                    i++
                }
            }
        }
        return arg.toString() to filters
    }

    /** `0` / `-1` / `1,3` / `1:3` / `[1,3]`（调用方已剥方括号） */
    private fun parseFilterToken(token: String, excluded: Boolean): List<Filter> {
        val t = token.trim()
        if (t.isEmpty()) return emptyList()

        val sepIdx = t.indexOfFirst { it == ',' || it == ':' }
        if (sepIdx > 0 && sepIdx < t.length - 1) {
            val a = t.substring(0, sepIdx).trim().toIntOrNull()
            val b = t.substring(sepIdx + 1).trim().toIntOrNull()
            if (a != null && b != null) {
                return listOf(if (excluded) Filter.ExcludeRange(a, b) else Filter.Range(a, b))
            }
        }
        val n = t.toIntOrNull() ?: return emptyList()
        return listOf(if (excluded) Filter.Exclude(n) else Filter.Pick(n))
    }

    /** 选择器非法 —— 书源作者的问题，上层要转成 `RuleSyntaxError` 报警告。 */
    class BadSelectorException(val selector: String, val detail: String) :
        RuntimeException("选择器非法：$selector（$detail）")
}
