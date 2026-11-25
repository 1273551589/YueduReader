package com.ayanami.reader.data

import android.content.Context
import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceImporter
import com.ayanami.reader.core.pipeline.SourceSession
import com.ayanami.reader.mod.ModLoader
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 极简持久化：一个 JSON 文件 + 一个书籍缓存目录。
 *
 * 方案 §2.2 的决策点选了 SQLite，但首版把「书源表 / 阅读位置」
 * 这类小数据先落 JSON —— 结构已按可迁移 shape 设计，换 Room 时只改这一层。
 */
class Repository(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "reader").apply { mkdirs() }
    }
    private val sourcesFile: File by lazy { File(dir, "bookSources.json") }
    private val settingsFile: File by lazy { File(dir, "settings.json") }
    private val progressFile: File by lazy { File(dir, "progress.json") }
    val modsDir: File by lazy { File(dir, "mods").apply { mkdirs() } }

    val gson = com.ayanami.reader.core.rule.LenientJson.lenient

    // ---------------- 书源 ----------------

    fun loadSources(): List<BookSource> {
        if (!sourcesFile.exists()) return emptyList()
        val text = runCatching { sourcesFile.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            gson.fromJson<List<BookSource>>(
                text, object : TypeToken<List<BookSource>>() {}.type
            )
        }.getOrNull() ?: emptyList()
    }

    fun saveSources(sources: List<BookSource>) {
        val arr = gson.toJsonTree(sources).asJsonArray
        sourcesFile.writeText(gson.toJson(arr))
    }

    fun importSources(json: String): SourceImporter.ImportResult {
        val existing = loadSources()
        val result = SourceImporter.parse(json, existing.map { it.bookSourceUrl }.toSet())
        if (result.sources.isNotEmpty()) {
            saveSources(existing + result.sources)
        }
        return result
    }

    fun exportSources(): String = SourceImporter.export(loadSources())

    fun deleteSource(url: String) {
        saveSources(loadSources().filterNot { it.bookSourceUrl == url })
    }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        val list = loadSources()
        list.firstOrNull { it.bookSourceUrl == url }?.bookSourceEnabled = enabled
        saveSources(list)
    }

    // ---------------- 体检结果 ----------------

    /**
     * 读体检结果。
     *
     * ⚠️ **必须走 `PersistedCheckResult` 这层扁平结构**。
     * `CheckResult.error` 是 sealed class，Gson 直接反序列化会返回 null，
     * 而它嵌在 Map 里 → **整个 Map 变成 null**，所有书源的体检结果一起丢失。
     * 实测：只要一条带 error，连"抽样可读"的绿标也会在重启后消失。
     */
    fun loadCheckResults(): Map<String, SourceChecker.CheckResult> {
        val f = File(dir, "checkResults.json")
        if (!f.exists()) return emptyMap()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyMap()
        val raw = runCatching {
            gson.fromJson<Map<String, PersistedCheckResult>>(
                text, object : TypeToken<Map<String, PersistedCheckResult>>() {}.type
            )
        }.getOrNull() ?: return emptyMap()
        return raw.mapValues { (_, v) -> v.toResult() }
    }

    /**
     * 保存一次体检结果，并维护「连续失败次数」。
     *
     * ⚠️ 之前这里每次只写 `mapOf(result.sourceUrl to failures)` ——
     * 多源时会把**别的源**的计数整个覆盖掉，只剩最后一个。
     * 连续失败 → 建议删除 这条功能因此永远只有一条有效数据。
     */
    fun saveCheckResult(result: SourceChecker.CheckResult) {
        // 只读一次 —— 之前这里读了两次，还留了个没用上的 `all` 变量（重构残留）
        val persisted = loadCheckResults().toMutableMap()
        persisted[result.sourceUrl] = result
        File(dir, "checkResults.json").writeText(
            gson.toJson(persisted.mapValues { (_, v) -> PersistedCheckResult.from(v) })
        )

        // 只读一次 counts —— 之前这里读了两次（算 failures 一次、写回又一次）
        val counts = nextFailureCounts(loadFailureCounts(), result)
        File(dir, "failures.json").writeText(gson.toJson(counts))
    }

    /** 各书源的连续失败次数。连续 3 次 → UI 标「建议删除」（方案 §4.2）。 */
    fun loadFailureCounts(): Map<String, Int> {
        val f = File(dir, "failures.json")
        if (!f.exists()) return emptyMap()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Int>>(
                text, object : TypeToken<Map<String, Int>>() {}.type
            )
        }.getOrNull() ?: emptyMap()
    }

    // ---------------- 阅读进度 ----------------

    fun saveProgress(bookUrl: String, chapterIndex: Int, chapterTitle: String) {
        val map = loadProgress()
        map[bookUrl] = mapOf(
            "index" to chapterIndex,
            "title" to chapterTitle,
            "time" to System.currentTimeMillis()
        )
        progressFile.writeText(gson.toJson(map))
    }

    fun loadProgress(): MutableMap<String, Map<String, Any>> {
        if (!progressFile.exists()) return mutableMapOf()
        val text = runCatching { progressFile.readText() }.getOrNull() ?: return mutableMapOf()
        return runCatching {
            gson.fromJson<MutableMap<String, Map<String, Any>>>(
                text, object : TypeToken<MutableMap<String, Map<String, Any>>>() {}.type
            )
        }.getOrNull() ?: mutableMapOf()
    }

    // ---------------- MOD ----------------

    fun loadMods(): ModLoader.LoadResult {
        val dirs = modsDir.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
        return ModLoader.load(dirs) { path ->
            val f = File(path)
            if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        }
    }

    /** 解一个 MOD 包（zip）。首版只支持「目录已在 mods/ 下」与 zip 解压。 */
    fun installModFromZip(zipFile: File): Result<String> {
        return runCatching {
            val name = zipFile.nameWithoutExtension
            val target = File(modsDir, name)
            if (target.exists()) throw IllegalStateException("同名 MOD 已存在：$name")
            target.mkdirs()
            java.util.zip.ZipFile(zipFile).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { it.name.contains("..") }
                    .forEach { entry ->
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
            }
            name
        }
    }

    // ---------------- 抓取入口 ----------------

    fun session(source: BookSource, polite: Boolean = false) = SourceSession(source, polite)

    suspend fun search(sources: List<BookSource>, keyword: String): Pair<List<Any>, List<Pair<String, String>>> {
        val hits = ArrayList<Any>()
        val errors = ArrayList<Pair<String, String>>()
        for (src in sources.filter { it.bookSourceEnabled }) {
            val s = SourceSession(src, polite = true)
            val r = runCatching { s.search(keyword) }.getOrElse { emptyList<com.ayanami.reader.core.model.SearchBook>() }
            if (r.isEmpty()) {
                errors.add(src.bookSourceName to (s.lastError?.humanMessage ?: "无结果"))
            } else hits.addAll(r)
        }
        return hits to errors
    }

    suspend fun bookInfo(source: BookSource, url: String): Book? =
        SourceSession(source).bookInfo(url)
}

/**
 * 计算「下一次」的连续失败计数表 —— 纯函数，便于单测。
 *
 * 规则：
 * - 本次 `READABLE` → 该源计数归零（但也保留在表里，便于 UI 显示"上次已恢复"）
 * - 本次失败 → 该源计数 +1
 * - **其他源的计数必须原样保留**（v0.3.3 之前这里是整表覆盖的 bug）
 */
internal fun nextFailureCounts(
    current: Map<String, Int>,
    result: SourceChecker.CheckResult
): Map<String, Int> {
    val out = current.toMutableMap()
    out[result.sourceUrl] = if (result.status == SourceChecker.UiStatus.READABLE) {
        0
    } else {
        (current[result.sourceUrl] ?: 0) + 1
    }
    return out
}

/**
 * 一条非致命警告的持久化形态。
 *
 * `kind` 声明成 `String?`：同 [com.ayanami.reader.core.rule.RuleErrorSnapshot.kind]，
 * Gson 会绕过 Kotlin 空检查塞 null，非空声明会在 `when(kind)` 里 NPE。
 */
internal data class PersistedWarning(
    val stage: String = "",
    val kind: String? = null,
    val arg: String? = null,
    val code: Int? = null
)

/**
 * 体检结果的持久化形态。
 *
 * 存在的唯一理由：`RuleError` 是 sealed class，Gson 无法还原抽象类型。
 * 把 error 摊平成 [RuleErrorSnapshot]，读回来再拼。
 */
internal data class PersistedCheckResult(
    val sourceUrl: String = "",
    val status: String = "UNVERIFIED",
    val elapsedMs: Long = 0,
    val stuckAt: String = "",
    val detail: String = "",
    val sampleBook: String? = null,
    val error: com.ayanami.reader.core.rule.RuleErrorSnapshot? = null,
    /** 非致命警告：阶段名 + 错误快照 */
    val warnings: List<PersistedWarning>? = null
) {
    fun toResult(): SourceChecker.CheckResult {
        val uiStatus = runCatching {
            SourceChecker.UiStatus.valueOf(status)
        }.getOrDefault(SourceChecker.UiStatus.UNVERIFIED)
        return SourceChecker.CheckResult(
            sourceUrl = sourceUrl,
            status = uiStatus,
            elapsedMs = elapsedMs,
            stuckAt = stuckAt,
            detail = detail,
            sampleBook = sampleBook,
            error = error?.toRuleError(),
            warnings = (warnings ?: emptyList()).map { w ->
                w.stage to com.ayanami.reader.core.rule.RuleErrorSnapshot(
                    kind = w.kind, arg = w.arg, code = w.code
                ).toRuleError()
            }
        )
    }

    companion object {
        fun from(r: SourceChecker.CheckResult) = PersistedCheckResult(
            sourceUrl = r.sourceUrl,
            status = r.status.name,
            elapsedMs = r.elapsedMs,
            stuckAt = r.stuckAt,
            detail = r.detail,
            sampleBook = r.sampleBook,
            error = com.ayanami.reader.core.rule.RuleErrorSnapshot.from(r.error),
            warnings = r.warnings.mapNotNull { (stage, e) ->
                com.ayanami.reader.core.rule.RuleErrorSnapshot.from(e)?.let { snap ->
                    PersistedWarning(stage, snap.kind, snap.arg, snap.code)
                }
            }
        )
    }
}
