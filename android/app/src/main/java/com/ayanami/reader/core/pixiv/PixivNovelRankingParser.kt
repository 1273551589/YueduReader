package com.ayanami.reader.core.pixiv

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 小说排行榜解析。
 *
 * ### 关键发现：榜单数据是**内嵌 JSON**，不是 HTML 结构
 *
 * 实测 `/novel/ranking.php` 的响应里带着 Next.js 的预加载数据，
 * 榜单条目在 `props.pageProps.serverSerializedPreloadedState`
 * （一个 **JSON 字符串**）→ `assign.display_a.rank_a` 数组：
 *
 * ```json
 * {"rank":1,"id":"29720928","title":"第二十二話　…",
 *  "user_id":"12070158","user_name":"ren.s",
 *  "profile_img":"https://i.pximg.net/user-profile/…",
 *  "character_count":"11163","word_count":"5486",
 *  "tag_a":["夢小説","ヒロアカ夢",…],
 *  "url":"https://i.pximg.net/c/100x100/novel-cover-master/…/ci29720928_…jpg",
 *  "series_id":16695172,"series_title":"世紀末に備えすぎた…",
 *  "bookmark_count":971,"x_restrict":"0","restrict":"0"}
 * ```
 *
 * **每个字段都有** —— 所以一次请求就能拿到能渲染卡片的全套数据。
 *
 * ### 为什么不用正则切 HTML
 *
 * 我先写了两版正则方案，都被真实页面打回：
 * 1. 按 `<div title="…"><a href="/novel/show.php?id=X">` 匹配 → 结构对但
 *    块长上限设成 8000，实测相邻条目间距到 8922，块被截断、作者/字数全丢
 * 2. 从 `novel-cover-master` 起点切块 → 页面里**另有一处** preload 区域
 *    （74738 字符附近）也有 novel id，但不是榜单条目，切出来的块全是垃圾
 *
 * 教训：**有结构化数据就别去解析 HTML**。`rank_a` 是 Pixiv 自己用的
 * 数据源，比 DOM 稳定得多，也不受 class 哈希改名影响。
 *
 * ### 保底
 *
 * 如果哪天 Pixiv 去掉了这段内嵌数据，[parse] 会退回「按 HTML 提 id」
 * 的浅解析（只有 id 和标题），至少不至于整页空白。
 */
object PixivNovelRankingParser {

    /**
     * 解析榜单页 HTML / JSON 响应。
     *
     * @param raw   `/novel/ranking.php?mode=X` 的响应体
     * @param limit 最多取多少条
     */
    fun parse(raw: String, limit: Int = 20): List<PixivNovel> {
        if (raw.isBlank()) return emptyList()

        // 主路径：从内嵌 JSON 里取 rank_a
        val arr = embeddedEntries(raw)
        if (arr != null && arr.size() > 0) {
            val out = ArrayList<PixivNovel>()
            for (idx in 0 until arr.size()) {
                if (out.size >= limit) break
                val el = arr.get(idx)
                if (!el.isJsonObject) continue
                runCatching { mapEntry(el.asJsonObject, idx + 1) }.getOrNull()
                    ?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                    ?.let { out.add(it) }
            }
            if (out.isNotEmpty()) return out
        }

        // 保底：按 HTML 提 id（只有 id 与标题，其余字段留空）
        return fallbackFromHtml(raw, limit)
    }

    // ---------------- 主路径：内嵌 JSON ----------------

    /**
     * 在响应里找出 `rank_a` 数组。
     *
     * 响应可能是：
     * - 纯 JSON（如果以后 Pixiv 提供 `format=json`）
     * - HTML，里面某处是 `"rank_a":[{…}]`
     *
     * 所以策略是：先尝试整体当 JSON 解析；失败则用字符串定位
     * `"rank_a":[` 并做括号配平截取（不用正则匹配嵌套 JSON，
     * 那在 tag 数组含 `]` 时会断在错误位置）。
     */
    internal fun embeddedEntries(raw: String): JsonArray? {
        // 1) 整串是 JSON
        runCatching {
            val root = JsonParser.parseString(raw)
            findRankArray(root)?.let { return it }
        }

        // 2) HTML 里找 `"rank_a":`
        val key = "\"rank_a\""
        var from = 0
        while (true) {
            val at = raw.indexOf(key, from)
            if (at < 0) return null
            val bracket = raw.indexOf('[', at + key.length)
            if (bracket < 0) return null
            val end = matchBracket(raw, bracket)
            if (end > bracket) {
                val candidate = raw.substring(bracket, end + 1)
                // ⚠️ **不要做反转义**。
                //
                // 我第一版写了 `\\"` → `"` 的转换，理由是"内嵌 JSON 是转义的"。
                // 实测这个假设是错的：页面里那段就是**合法 JSON**
                // （`"rank_a":[{"rank":1,"id":"…"}]`），
                // 反转义会把 `\\"` 变成 `"` 以外的破坏，Gson 直接解析失败，
                // 于是所有条目都走了「只有 id 和标题」的保底路径 ——
                // 界面上就是作者/字数全空的空白卡片。
                val arr = runCatching {
                    JsonParser.parseString(candidate).asJsonArray
                }.getOrNull()
                if (arr != null && arr.size() > 0 && arr[0].isJsonObject) {
                    val first = arr[0].asJsonObject
                    if (first.has("id") || first.has("rank")) return arr
                }
            }
            from = at + key.length
        }
    }

    /** 递归找形如 `{rank_a:[...]}` 的数组。 */
    private fun findRankArray(el: com.google.gson.JsonElement): JsonArray? {
        if (el.isJsonObject) {
            val o = el.asJsonObject
            o.get("rank_a")?.takeIf { it.isJsonArray }?.let { return it.asJsonArray }
            for ((_, v) in o.entrySet()) {
                findRankArray(v)?.let { return it }
            }
        } else if (el.isJsonArray) {
            for (v in el.asJsonArray) {
                findRankArray(v)?.let { return it }
            }
        } else if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
            // 预加载数据本身是一个"字符串套 JSON"
            val s = el.asString
            if (s.length > 2 && s.trimStart().startsWith("{")) {
                val inner = runCatching { JsonParser.parseString(s) }.getOrNull()
                if (inner != null) findRankArray(inner)?.let { return it }
            }
        }
        return null
    }

    /** 从 `[` 开始做括号配平，返回匹配的 `]` 下标（考虑字符串内的括号）。 */
    private fun matchBracket(s: String, open: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '[' -> depth++
                !inStr && c == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * 还原「JSON 里的 JSON 字符串」。
     *
     * 内嵌数据长这样：`\"title\":\"第二十二話\"`。
     * 只做最小必要的反转义 —— 不要把 `\n` 也还原（正文里不该出现，
     * 而 comment 字段里的 `\n` 我们不需要）。
     */
    internal fun unescapeJsonText(s: String): String =
        s.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    /** 把一个 `rank_a` 元素映射成 [PixivNovel]。 */
    private fun mapEntry(o: JsonObject, fallbackRank: Int): PixivNovel {
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
            ?: o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val id = s("id")
        val tags = o.get("tag_a")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { t -> t.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()

        return PixivNovel(
            id = id,
            title = s("title"),
            seriesTitle = s("series_title"),
            authorName = s("user_name"),
            authorId = s("user_id"),
            authorAvatar = s("profile_img"),
            coverUrl = s("url"),
            tags = tags,
            bookmarkCount = i("bookmark_count"),
            wordCount = i("character_count").takeIf { it > 0 } ?: i("word_count"),
            xRestrict = i("x_restrict"),
            rank = i("rank").takeIf { it > 0 } ?: fallbackRank,
            createDate = s("create_date"),
            webUrl = "https://www.pixiv.net/novel/show.php?id=$id"
        )
    }

    // ---------------- 保底：从 HTML 提 id ----------------

    /**
     * 最坏情况：只拿到 id 和标题。
     *
     * 卡片会缺作者/字数/收藏，但至少列表不是空的。
     * 优先「带 title 属性的标题链接」那条路（比裸 id 多一个标题）。
     */
    private fun fallbackFromHtml(raw: String, limit: Int): List<PixivNovel> {
        val ids = LinkedHashMap<String, Int>()
        Regex("""/novel/show\.php\?id=(\d+)""").findAll(raw).forEach { m ->
            val id = m.groupValues[1]
            if (id !in ids) ids[id] = m.range.first
        }
        if (ids.isEmpty()) return emptyList()

        return ids.keys.take(limit).mapIndexed { idx, id ->
            val title = Regex(
                """<div[^>]*title="([^"]{2,300})"[^>]*>\s*<a[^>]*href="/novel/show\.php\?id=${Regex.escape(id)}""""
            ).find(raw)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()

            PixivNovel(
                id = id,
                title = title,
                rank = idx + 1,
                webUrl = "https://www.pixiv.net/novel/show.php?id=$id"
            )
        }
    }

    /** HTML 实体解码。 */
    internal fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // ---------------- 兼容旧接口 ----------------

    /** `11,163文字` / `1.2万字` / `982字` → Int（保留给测试与兼容）。 */
    internal fun parseWordCount(lines: List<String>): Int {
        for (line in lines) {
            val m = Regex("""([\d,]+(?:\.\d+)?)\s*(万|千|百)?\s*(?:文字|字)""").find(line)
                ?: continue
            val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            val mult = when (m.groupValues[2]) {
                "万" -> 10_000
                "千" -> 1_000
                "百" -> 100
                else -> 1
            }
            return (num * mult).toInt()
        }
        return 0
    }

    /** 保留给旧的 HTML 保底路径测试。 */
    internal fun parseBookmark(lines: List<String>): Int {
        var sawDuration = false
        for (line in lines) {
            if (!sawDuration && Regex("""^\d+分$""").matches(line)) {
                sawDuration = true
                continue
            }
            if (sawDuration && Regex("""^\d{1,7}$""").matches(line)) {
                return line.toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
