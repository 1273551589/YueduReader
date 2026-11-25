package com.ayanami.reader.core.rule

/** URL 相对地址解析（对应 `NetworkUtils.resolve` 的语义）。 */
object UrlHelper {

    fun resolve(base: String, relative: String): String {
        if (relative.isBlank()) return base
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (base.isBlank()) return relative

        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return relative
        val scheme = base.substring(0, schemeEnd)
        val afterScheme = base.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        val authority = if (pathStart < 0) afterScheme else afterScheme.substring(0, pathStart)
        val basePath = if (pathStart < 0) "/" else afterScheme.substring(pathStart)

        if (relative.startsWith("//")) return "$scheme:$relative"
        if (relative.startsWith("/")) return "$scheme://$authority$relative"

        val dir = basePath.substringBeforeLast('/', "/")
        val merged = if (dir == "/") "/$relative" else "$dir/$relative"
        return "$scheme://$authority${normalize(merged)}"
    }

    private fun normalize(path: String): String {
        val parts = path.substringBefore('?').replace("?", "\u0000").let { it }
        val query = if (path.contains("?")) "?" + path.substringAfter("?") else ""
        val segs = path.substringBefore("?").split('/')
        val out = ArrayList<String>()
        for (s in segs) {
            when (s) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(s)
            }
        }
        return "/" + out.joinToString("/") + query
    }
}
