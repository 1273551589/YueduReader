package com.ayanami.reader.core.rule

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.reflect.TypeToken

/**
 * 规则里出现的 JSON 一律走这里。
 *
 * 两条路径对应 Legado 的行为（§3.3.1 ②）：
 * - [strict] = `GSONStrict`，`setStrictness(Strictness.STRICT)`
 * - [lenient] = `GSON`，实际是 `LEGACY_STRICT`
 *
 * `@put` 先走严格路径，失败再走宽松路径 —— 不能只实现一条。
 */
object LenientJson {

    val lenient: Gson = GsonBuilder().serializeNulls().create()

    val strict: Gson = GsonBuilder()
        .serializeNulls()
        .setStrictness(Strictness.STRICT)
        .create()

    fun parseStringMap(json: String): Map<String, String>? {
        val type = object : TypeToken<Map<String, String>>() {}.type
        // 严格优先，宽松兜底 —— 与 Legado 的 splitPutRule 同序
        val strictResult: Map<String, String>? =
            runCatching { strict.fromJson<Map<String, String>>(json, type) }.getOrNull()
        if (strictResult != null) return strictResult
        val lenientResult: Map<String, String>? =
            runCatching { lenient.fromJson<Map<String, String>>(json, type) }.getOrNull()
        return lenientResult
    }

    fun parseElement(json: String): JsonElement? {
        return runCatching { lenient.fromJson(json, JsonElement::class.java) }.getOrNull()
    }

    fun parseObject(json: String): JsonObject? {
        return runCatching { lenient.fromJson(json, JsonObject::class.java) }.getOrNull()
    }

    /** `url,{json}` 的第二段。失败返回 null（调用方回退到宽松解析）。 */
    fun parseUrlOption(json: String): UrlOption? {
        val strictResult: UrlOption? =
            runCatching { strict.fromJson(json, UrlOption::class.java) }.getOrNull()
        if (strictResult != null) return strictResult
        return runCatching { lenient.fromJson(json, UrlOption::class.java) }.getOrNull()
    }
}

/** `url,{"method":"POST",...}` 的选项对象（`AnalyzeUrl.kt:682` 的 UrlOption）。 */
data class UrlOption(
    var method: String? = null,
    var body: Any? = null,
    var headers: Map<String, String>? = null,
    var charset: String? = null,
    var webView: Boolean? = null,
    var retry: Int? = null,
    var type: String? = null
)
