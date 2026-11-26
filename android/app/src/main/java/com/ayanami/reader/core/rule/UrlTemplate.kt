package com.ayanami.reader.core.rule

import java.nio.charset.Charset

/**
 * 请求 URL / Body 的模板展开 —— Legado `AnalyzeUrl` 的对应物。
 *
 * 这是 v0.4.0 第三个阻塞级 bug 的修复点。之前 `SourceSession` 里只有
 *
 * ```kotlin
 * searchUrl.replace("{{key}}", encode(keyword))
 * ```
 *
 * 三个缺口：
 *
 * 1. **相对地址没拼基地址** —— 书源写 `/s.php?q={{key}}` 时直接拿它去
 *    `URL("/s.php...")` 会抛 `MalformedURLException`，整源搜索失败。
 * 2. **POST body 的 `{{key}}` 没替换** —— 大量站点的搜索是 POST 表单，
 *    不发关键词等于搜空。
 * 3. **字符集编码用死 UTF-8** —— 声明了 `charset: gbk` 的老站收到
 *    UTF-8 百分号编码会得到乱码关键词，服务端直接返回 0 条。
 *
 * 这里把三件事收口成纯函数，方便单测直接打。
 */
object UrlTemplate {

    /**
     * 展开模板。
     *
     * @param template  原始串（可能是 `url,{json}` 的第一段，也可能直接是 body）
     * @param vars      变量表。`key` 是搜索关键词，`page` 是页码
     * @param charset   用于百分号编码的字符集（关键：GBK 站必须用 GBK）
     * @param baseUrl   拼相对地址用。为空则不拼
     * @param encode    是否对变量做 URL 编码。body 用 form 编码时也要编
     */
    fun render(
        template: String,
        vars: Map<String, String>,
        charset: String? = null,
        baseUrl: String = "",
        encode: Boolean = true
    ): String {
        val cs = runCatching { Charset.forName(charset ?: "UTF-8") }
            .getOrDefault(Charsets.UTF_8)

        var out = template
        for ((k, v) in vars) {
            val replacement = if (encode) percentEncode(v, cs) else v
            out = out.replace("{{$k}}", replacement)
            // 书源里也常见 `{key}` 单花括号写法（部分老源）
            if (k == "key") out = out.replace("{key}", replacement)
        }

        if (baseUrl.isNotBlank()) out = resolveIfRelative(baseUrl, out)
        return out
    }

    /**
     * 只对 `Unreserved` 之外的字符做百分号编码，且**空格编成 `+`**
     * —— 表单站（尤其 GBK 的）认 `+`，不认 `%20`。
     */
    fun percentEncode(s: String, charset: Charset = Charsets.UTF_8): String {
        val bytes = s.toByteArray(charset)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> sb.append(ch)
                ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * 相对地址拼接。
     *
     * 只在目标串**不是**绝对地址时才动手；查询串里的 `//` 不算协议相对。
     */
    fun resolveIfRelative(baseUrl: String, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("data:") || u.startsWith("javascript:")) return u
        return UrlHelper.resolve(baseUrl, u)
    }

    /**
     * 拆 `url,{json}` 形态。
     *
     * 逗号必须是**顶层**的 —— 很多 url 本身带逗号（`?ids=1,2,3`），
     * 而 `{` 里也有逗号。所以从后往前找第一个 `{`，看它前面是不是逗号。
     */
    fun splitUrlOption(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        val brace = trimmed.indexOf('{')
        if (brace > 0 && trimmed[brace - 1] == ',') {
            return trimmed.substring(0, brace - 1).trim() to trimmed.substring(brace)
        }
        // `url,{...}` 里可能还有空格
        val comma = findTopLevelComma(trimmed)
        if (comma < 0) return trimmed to null
        val url = trimmed.substring(0, comma)
        val json = trimmed.substring(comma + 1)
        if (!json.trimStart().startsWith("{")) return trimmed to null
        return url to json
    }

    private fun findTopLevelComma(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) return i
            }
        }
        return -1
    }
}
