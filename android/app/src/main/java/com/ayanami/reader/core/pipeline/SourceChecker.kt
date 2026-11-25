package com.ayanami.reader.core.pipeline

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.rule.RuleError

/**
 * 书源体检 —— 砍到「一次抽样、两个结果、一个耗时」（方案 §4）。
 *
 * 内部分四阶段串行，但界面只显示状态 + 总耗时 + 卡在哪一步。
 * 并发恒为 1：同一源绝不并发（方案 §4.5 的硬规定）。
 */
object SourceChecker {

    /** UI 状态（给人看，6 个值，方案 §4.3 A）。 */
    enum class UiStatus(val label: String) {
        READABLE("抽样可读"),
        CONNECT_FAILED("连接失败"),
        NO_CONTENT("未取到正文"),
        NEEDS_LOGIN("需登录或人机验证"),
        UNSUPPORTED("当前执行端不支持"),
        UNVERIFIED("未完成验证")
    }

    data class CheckResult(
        val sourceUrl: String,
        val status: UiStatus,
        val elapsedMs: Long,
        /** 卡在哪一步：search / bookInfo / toc / content / done */
        val stuckAt: String,
        val detail: String = "",
        /** 抽样到的书名，方便人核对 */
        val sampleBook: String? = null,
        /** 引擎层的结构化错误，便于排查 */
        val error: RuleError? = null,
        /** 非致命规则错误（能跑通但字段可能是错的）。v0.3.6 新增 */
        val warnings: List<Pair<String, RuleError>> = emptyList()
    )

    /**
     * @param testBook 指定书名；为空时用 [defaultKeywords] 逐个试
     */
    suspend fun check(
        source: BookSource,
        testBook: String? = null,
        polite: Boolean = true,
        maxChapters: Int = 3
    ): CheckResult {
        val started = System.currentTimeMillis()
        com.ayanami.reader.core.net.HttpFetcher.requestCount.set(0)
        com.ayanami.reader.core.net.HttpFetcher.requestLog.clear()
        val session = SourceSession(source, polite)

        var stage = "search"
        try {
            val keywords = if (!testBook.isNullOrBlank()) listOf(testBook) else defaultKeywords
            var book = null as com.ayanami.reader.core.model.SearchBook?
            var lastErr: RuleError? = null

            for (kw in keywords) {
                val hits = runCatching { session.search(kw) }.getOrElse { emptyList() }
                lastErr = session.lastError
                if (hits.isNotEmpty()) {
                    book = hits.first()
                    break
                }
            }
            if (book == null) {
                val err = lastErr ?: RuleError.SelectorEmpty("search")
                return finish(source, started, stage, err, sample = null)
            }

            // 搜索阶段成功，但过程中若有过规则错误，记成警告
            session.lastError?.let { session.noteWarning("search", it) }

            stage = "bookInfo"
            val info = runCatching { session.bookInfo(book.bookUrl) }.getOrNull()
            if (info == null) {
                return finish(source, started, stage, session.lastError, book.name)
            }

            session.lastError?.let { session.noteWarning("bookInfo", it) }

            stage = "toc"
            val toc = runCatching { session.toc(info) }.getOrElse { emptyList() }
            if (toc.isEmpty()) {
                return finish(source, started, stage, session.lastError, info.name)
            }

            session.lastError?.let { session.noteWarning("toc", it) }

            stage = "content"
            // 取前 N 个非空、非纯目录提示的章节，最多试 maxChapters 章
            var got = false
            var lastContentErr: RuleError? = null
            for (chapter in toc.take(maxChapters * 2)) {
                if (isTocNoise(chapter.title)) continue
                // 体检只判断"能否读到正文"，不抓完整分页 ——
                // 之前一章正文分 20 页就发 20 次请求，体检从 3s 涨到 9s
                val text = runCatching { session.content(chapter, maxPages = 2) }.getOrNull()
                if (text == null) {
                    lastContentErr = session.lastError
                    continue
                }
                if (isReadable(text.text)) {
                    got = true
                    break
                }
            }
            if (!got) {
                return finish(
                    source, started, stage,
                    lastContentErr ?: RuleError.SelectorEmpty("content"),
                    info.name
                )
            }

            return CheckResult(
                sourceUrl = source.bookSourceUrl,
                status = UiStatus.READABLE,
                elapsedMs = System.currentTimeMillis() - started,
                stuckAt = "done",
                detail = buildDetail(session),
                sampleBook = info.name,
                error = null,
                warnings = session.warnings.toList()
            )
        } catch (e: SourceSession.UnsupportedRuleException) {
            return finish(source, started, stage, session.lastError, null, UiStatus.UNSUPPORTED)
        } catch (e: Exception) {
            return finish(
                source, started, stage,
                RuleError.NetworkRefused(e.message ?: e.javaClass.simpleName), null
            )
        }
    }

    /**
     * 报告正文：四步通 + 请求数；若有非致命规则错误，追加警告。
     *
     * 之前只写"四步通"，于是「书名那条正则写错但流程能跑」这种情况
     * 完全看不出来 —— 状态是绿的，字段是错的。
     */
    private fun buildDetail(session: SourceSession): String {
        val base = "搜索/详情/目录/正文 四步通（" +
            "${com.ayanami.reader.core.net.HttpFetcher.requestCount.get()} 次请求）"
        if (session.warnings.isEmpty()) return base
        val summary = session.warnings.joinToString("；") { (stage, e) ->
            "$stage: ${e.humanMessage}"
        }
        return "$base · ⚠ $summary"
    }

    private fun finish(
        source: BookSource,
        started: Long,
        stage: String,
        error: RuleError?,
        sample: String?,
        forced: UiStatus? = null
    ): CheckResult {
        val ui = forced ?: when (error) {
            is RuleError.HttpStatus -> when (error.code) {
                401, 403 -> UiStatus.NEEDS_LOGIN
                else -> UiStatus.CONNECT_FAILED
            }
            is RuleError.NetworkTimeout, is RuleError.NetworkRefused -> UiStatus.CONNECT_FAILED
            is RuleError.RequiresLogin -> UiStatus.NEEDS_LOGIN
            is RuleError.UnsupportedApi -> UiStatus.UNSUPPORTED
            is RuleError.SelectorEmpty -> UiStatus.NO_CONTENT
            is RuleError.JsThrown, is RuleError.JsTimeout -> UiStatus.UNSUPPORTED
            is RuleError.SkippedStage -> UiStatus.UNVERIFIED
            is RuleError.DecodeError, is RuleError.RuleSyntaxError -> UiStatus.NO_CONTENT
            is RuleError.RegexTimeout -> UiStatus.NO_CONTENT
            null -> UiStatus.UNVERIFIED
        }
        return CheckResult(
            sourceUrl = source.bookSourceUrl,
            status = ui,
            elapsedMs = System.currentTimeMillis() - started,
            stuckAt = stage,
            detail = error?.humanMessage ?: "",
            sampleBook = sample,
            error = error
        )
    }

    /** 「合理正文」的可执行判据（方案 §5.5 的意图，首版给保守阈值）。 */
    fun isReadable(text: String): Boolean {
        val t = text.trim()
        if (t.length < 80) return false
        val cjk = t.count { it.code in 0x4E00..0x9FFF }
        // 中文占比过低说明可能是乱码或只剩导航
        if (cjk.toFloat() / t.length < 0.15f) return false
        // 目录提示
        if (t.contains("目录") && t.length < 200) return false
        return true
    }

    private fun isTocNoise(title: String): Boolean {
        val t = title.trim()
        return t.contains("封面") || t.contains("彩插") || t.contains("版权") ||
            t.contains("插图") || t.contains("目录")
    }

    val defaultKeywords = listOf("转生", "魔法", "校园")
}
