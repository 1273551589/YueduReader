package com.ayanami.reader.core.pipeline

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.rule.LenientJson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 书源导入（方案 §3.2.6）。
 *
 * 支持三种形态：
 * - JSON 数组 `[源, 源, ...]`
 * - 单个对象 `{源}`
 * - 分组对象 `{"组名": [源, ...], "组名2": [...]}`
 *
 * 按 `bookSourceUrl` 去重；坏条目单独列出而不是整批失败。
 */
object SourceImporter {

    data class ImportResult(
        val sources: List<BookSource>,
        val duplicates: List<BookSource>,
        val invalid: List<InvalidEntry>,
        val groupNames: List<String> = emptyList()
    ) {
        val totalCount: Int get() = sources.size + duplicates.size + invalid.size
    }

    data class InvalidEntry(val index: Int, val reason: String, val snippet: String)

    fun parse(json: String, existingUrls: Set<String> = emptySet()): ImportResult {
        val valid = ArrayList<BookSource>()
        val dups = ArrayList<BookSource>()
        val invalid = ArrayList<InvalidEntry>()
        val groups = ArrayList<String>()

        val root = LenientJson.parseElement(json)
            ?: return ImportResult(emptyList(), emptyList(), listOf(InvalidEntry(-1, "JSON 解析失败", json.take(120))))

        val entries = ArrayList<Pair<String?, JsonObject>>()
        when {
            root is JsonArray -> root.forEach { el ->
                (el as? JsonObject)?.let { entries.add(null to it) }
            }
            root is JsonObject -> {
                // 是"一个书源"还是"分组导出"？
                if (root.has("bookSourceUrl") || root.has("bookSourceName")) {
                    entries.add(null to root)
                } else {
                    root.entrySet().forEach { (k, v) ->
                        if (v is JsonArray) {
                            groups.add(k)
                            v.forEach { el -> (el as? JsonObject)?.let { entries.add(k to it) } }
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            return ImportResult(emptyList(), emptyList(), listOf(InvalidEntry(-1, "未找到任何书源条目", "")))
        }

        val seen = HashSet<String>()
        entries.forEachIndexed { idx, entry ->
            val group = entry.first
            val obj = entry.second
            val url = obj.get("bookSourceUrl")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val name = obj.get("bookSourceName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            if (url.isEmpty()) {
                invalid.add(InvalidEntry(idx, "缺少 bookSourceUrl", obj.toString().take(120)))
            } else if (name.isEmpty()) {
                invalid.add(InvalidEntry(idx, "缺少 bookSourceName", obj.toString().take(120)))
            } else {
                val parsed = runCatching {
                    LenientJson.lenient.fromJson(obj, BookSource::class.java)
                }.getOrNull()
                if (parsed == null) {
                    invalid.add(InvalidEntry(idx, "字段反序列化失败", obj.toString().take(120)))
                } else {
                    parsed.rawJson = obj.toString()
                    if (group != null) parsed.bookSourceGroup = group
                    // 同批内 + 已存在 都算重复
                    if (!seen.add(url) || existingUrls.contains(url)) dups.add(parsed)
                    else valid.add(parsed)
                }
            }
        }

        return ImportResult(valid, dups, invalid, groups)
    }

    /** 导出成 Legado 兼容的数组。未编辑过的源原样回写 raw。 */
    fun export(sources: List<BookSource>): String {
        val arr = JsonArray()
        sources.forEach { s ->
            val el: JsonElement = s.rawJson?.let { LenientJson.parseElement(it) }
                ?: LenientJson.lenient.toJsonTree(s)
            arr.add(el)
        }
        return LenientJson.lenient.toJson(arr)
    }
}
