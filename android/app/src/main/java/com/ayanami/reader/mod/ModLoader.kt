package com.ayanami.reader.mod

import com.google.gson.JsonObject
import androidx.compose.ui.graphics.Color
import com.ayanami.reader.core.rule.LenientJson

/**
 * 声明式 MOD（方案 §8.5④，路线 A：纯声明，零代码执行）。
 *
 * MOD 只能改「配置」，不能带脚本 —— 这是为了守住「书源是你导入的」这个
 * 信任边界不被扩大到「MOD 作者」。
 *
 * 目录结构（落到 app 私有目录）：
 * ```
 * mods/
 *   my-theme/
 *     mod.json      { id, name, version, author, type, entry, priority }
 *     theme.json    颜色 / 圆角 / 间距
 *   my-sources/
 *     mod.json
 *     sources/ 下放 Legado 格式书源包（任意 .json）
 * ```
 *
 * 加载规则照 MC 的教训：坏的跳过并记日志（不崩），未知 type 直接忽略，
 * 同 type 按 priority 排序，不支持热加载，每个 MOD 可单独禁用。
 */
object ModLoader {

    enum class ModType { theme, sources, categories, cleaner, unknown }

    data class ModManifest(
        val id: String,
        val name: String,
        val version: String = "0.0.0",
        val author: String = "",
        val type: ModType = ModType.unknown,
        val entry: String? = null,
        val priority: Int = 100,
        val minAppVersion: String? = null
    )

    data class LoadedMod(
        val manifest: ModManifest,
        val dirPath: String,
        val theme: ThemeTokens? = null,
        val sourceJson: String? = null,
        val categoryMapping: Map<String, String> = emptyMap()
    )

    data class LoadResult(
        val mods: List<LoadedMod>,
        val errors: List<String>
    )

    /**
     * @param modDirs 已解出的 MOD 目录绝对路径列表
     * @param readText 由调用方注入的读文件实现（Android 侧走 File，测试侧走内存）
     */
    fun load(
        modDirs: List<String>,
        readText: (String) -> String?
    ): LoadResult {
        val out = ArrayList<LoadedMod>()
        val errors = ArrayList<String>()

        for (dir in modDirs) {
            val manifestText = readText("$dir/mod.json")
            if (manifestText == null) {
                errors.add("$dir：缺少 mod.json，已跳过")
                continue
            }
            val obj = runCatching { LenientJson.parseObject(manifestText) }.getOrNull()
            if (obj == null) {
                errors.add("$dir：mod.json 不是合法 JSON，已跳过")
                continue
            }
            val manifest = parseManifest(obj)
            if (manifest.id.isBlank()) {
                errors.add("$dir：mod.json 缺少 id，已跳过")
                continue
            }
            if (manifest.type == ModType.unknown) {
                // 未知 type 直接忽略，不报错 —— 前向兼容
                continue
            }

            var theme: ThemeTokens? = null
            var sourceJson: String? = null
            var mapping = emptyMap<String, String>()

            when (manifest.type) {
                ModType.theme -> {
                    val entry = manifest.entry ?: "theme.json"
                    val text = readText("$dir/$entry")
                    if (text == null) {
                        errors.add("${manifest.id}：theme 缺少 $entry")
                    } else {
                        theme = runCatching { parseTheme(text) }.getOrNull()
                        if (theme == null) errors.add("${manifest.id}：theme.json 解析失败")
                    }
                }
                ModType.sources -> {
                    val entry = manifest.entry ?: "sources/sources.json"
                    val text = readText("$dir/$entry")
                    if (text == null) errors.add("${manifest.id}：sources 缺少 $entry")
                    else sourceJson = text
                }
                ModType.categories -> {
                    val entry = manifest.entry ?: "mapping.json"
                    val text = readText("$dir/$entry")
                    if (text == null) errors.add("${manifest.id}：categories 缺少 $entry")
                    else mapping = runCatching {
                        LenientJson.parseStringMap(text) ?: emptyMap()
                    }.getOrDefault(emptyMap())
                }
                else -> Unit
            }

            out.add(LoadedMod(manifest, dir, theme, sourceJson, mapping))
        }

        // 同 type 按 priority 升序（小的先生效，后覆盖前者）
        val sorted = out.sortedWith(
            compareBy<LoadedMod> { it.manifest.type.ordinal }
                .thenBy { it.manifest.priority }
        )
        return LoadResult(sorted, errors)
    }

    private fun parseManifest(obj: JsonObject): ModManifest {
        fun str(k: String): String = obj.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
        fun int(k: String, def: Int): Int =
            obj.get(k)?.takeIf { !it.isJsonNull }?.asInt ?: def

        val type = when (str("type").lowercase()) {
            "theme" -> ModType.theme
            "sources", "source" -> ModType.sources
            "categories", "category" -> ModType.categories
            "cleaner", "purify" -> ModType.cleaner
            else -> ModType.unknown
        }
        return ModManifest(
            id = str("id"),
            name = str("name").ifBlank { str("id") },
            version = str("version").ifBlank { "0.0.0" },
            author = str("author"),
            type = type,
            entry = str("entry").ifBlank { null },
            priority = int("priority", 100),
            minAppVersion = str("minAppVersion").ifBlank { null }
        )
    }

    private fun parseTheme(text: String): ThemeTokens {
        val obj = LenientJson.parseObject(text) ?: throw IllegalArgumentException("bad theme json")
        fun color(k: String): Color? =
            obj.get(k)?.takeIf { !it.isJsonNull }?.asString?.let { parseColor(it) }
        fun dp(k: String): Float? =
            obj.get(k)?.takeIf { !it.isJsonNull }?.asFloat

        return ThemeTokens(
            primary = color("primary"),
            background = color("background"),
            surface = color("surface"),
            textPrimary = color("textPrimary"),
            textSecondary = color("textSecondary"),
            accent = color("accent"),
            cornerRadius = dp("cornerRadius"),
            spacing = dp("spacing")
        )
    }

    fun parseColor(raw: String): Color? {
        val s = raw.trim().removePrefix("#")
        return when (s.length) {
            6 -> Color(0xFF000000 or s.toLong(16))
            8 -> Color(s.toLong(16))
            else -> null
        }
    }

    /** theme.json 映射出来的令牌，直接喂给 Compose 主题。 */
    data class ThemeTokens(
        val primary: Color? = null,
        val background: Color? = null,
        val surface: Color? = null,
        val textPrimary: Color? = null,
        val textSecondary: Color? = null,
        val accent: Color? = null,
        val cornerRadius: Float? = null,
        val spacing: Float? = null
    )
}
