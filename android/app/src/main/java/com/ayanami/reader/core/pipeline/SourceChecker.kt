package com.ayanami.reader.core.pipeline

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.rule.RuleError

/**
 * 书源体检 —— 「一次抽样、四段校验、两个结果、一个耗时」。
 *
 * ### v0.4.0 的严格化
 *
 * 以前只要 HTTP 200 且搜索页能解析出东西就叫「可读」。实测结果是：
 * 一堆标着绿标的源，点进去目录是空的、正文是「请登录后阅读」。
 *
 * 现在四段必须**全部**通过，缺一段就判失败并指明卡在哪：
 *
 * 1. **搜索/发现** —— 能解析出 `书名 + 详情URL` 都非空的书目
 * 2. **详情** —— 能进详情页拿到目录地址
 * 3. **目录** —— 至少 1 章有效章节（标题 + 地址都非空）
 * 4. **正文** —— 抽检正文 >50 字，且**未命中假正文特征**
 *    （请登录 / 转码中 / VIP 订阅提示 / 正文加载中）
 *
 * 并发恒为 1：同一源绝不并发（方案 §4.5 的硬规定）。
 */
object SourceChecker {

    /** UI 状态（给人看，6 个值）。 */
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
        val warnings: List<Pair<String, RuleError>> = emptyList(),
        /** 四段各自的通过情况，UI 上可以展开看。v0.4.0 新增 */
        val stages: List<StageResult> = emptyList()
    ) {
        /** 四段是否全通。 */
        val allStagesPassed: Boolean
            get() = stages.isNotEmpty() && stages.all { it.passed }
    }

    /** 单个阶段的结论。 */
    data class StageResult(
        val name: String,        // search / bookInfo / toc / content
        val passed: Boolean,
        val note: String = ""
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
        val stages = ArrayList<StageResult>()

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
                stages.add(StageResult("search", false, lastErr?.humanMessage ?: "无结果"))
                val err = lastErr ?: RuleError.SelectorEmpty("search")
                return finish(source, started, stage, err, sample = null, stages = stages)
            }
            // 严格：书名和详情地址都必须非空（以前只看列表非空）
            if (book.name.isBlank() || book.bookUrl.isBlank()) {
                stages.add(StageResult("search", false, "书目缺书名或详情地址"))
                return finish(
                    source, started, stage,
                    RuleError.SelectorEmpty("search.items"),
                    sample = book.name, stages = stages
                )
            }
            stages.add(StageResult("search", true, book.name))

            // 搜索阶段成功，但过程中若有过规则错误，记成警告
            session.lastError?.let { session.noteWarning("search", it) }

            stage = "bookInfo"
            val info = runCatching { session.bookInfo(book.bookUrl) }.getOrNull()
            if (info == null) {
                stages.add(StageResult("bookInfo", false, session.lastError?.humanMessage ?: "详情页失败"))
                return finish(source, started, stage, session.lastError, book.name, stages = stages)
            }
            if (info.name.isBlank() || info.tocUrl.isBlank()) {
                stages.add(StageResult("bookInfo", false, "缺书名或目录地址"))
                return finish(
                    source, started, stage,
                    RuleError.SelectorEmpty("bookInfo.tocUrl"),
                    info.name, stages = stages
                )
            }
            stages.add(StageResult("bookInfo", true, info.tocUrl))

            session.lastError?.let { session.noteWarning("bookInfo", it) }

            stage = "toc"
            val toc = runCatching { session.toc(info) }.getOrElse { emptyList() }
            if (toc.isEmpty()) {
                stages.add(StageResult("toc", false, session.lastError?.humanMessage ?: "目录为空"))
                return finish(source, started, stage, session.lastError, info.name, stages = stages)
            }
            // 严格：至少一章「标题 + 地址」都有效
            val validChapters = toc.count { it.title.isNotBlank() && it.url.startsWith("http") }
            if (validChapters == 0) {
                stages.add(StageResult("toc", false, "章节缺标题或地址"))
                return finish(
                    source, started, stage,
                    RuleError.SelectorEmpty("toc.items"),
                    info.name, stages = stages
                )
            }
            stages.add(StageResult("toc", true, "$validChapters 章"))

            session.lastError?.let { session.noteWarning("toc", it) }

            stage = "content"
            // 抽检前 N 个非纯目录提示的章节
            var got = false
            var fake: FakeContent? = null
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
                val verdict = judgeContent(text.text)
                if (verdict == null) {
                    got = true
                    break
                }
                // 登录墙的结论比"过短/乱码"更有价值 ——
                // 别让后面几章的更泛结论把墙覆盖掉
                if (fake == null || (verdict.loginWall && !fake.loginWall)) {
                    fake = verdict
                }
            }

            if (!got) {
                if (fake != null) {
                    // 假正文（登录墙 / 转码中）—— 这不算「没内容」，
                    // 要明确告诉用户是被墙了，否则他会以为书源坏了
                    stages.add(StageResult("content", false, fake.reason))
                    return finish(
                        source, started, stage,
                        if (fake.loginWall) RuleError.RequiresLogin(fake.evidence)
                        else RuleError.SelectorEmpty("content：${fake.reason}"),
                        info.name,
                        forced = if (fake.loginWall) UiStatus.NEEDS_LOGIN else UiStatus.NO_CONTENT,
                        stages = stages
                    )
                }
                stages.add(
                    StageResult("content", false, lastContentErr?.humanMessage ?: "正文为空或过短")
                )
                return finish(
                    source, started, stage,
                    lastContentErr ?: RuleError.SelectorEmpty("content"),
                    info.name, stages = stages
                )
            }
            stages.add(StageResult("content", true, "抽检通过"))

            return CheckResult(
                sourceUrl = source.bookSourceUrl,
                status = UiStatus.READABLE,
                elapsedMs = System.currentTimeMillis() - started,
                stuckAt = "done",
                detail = buildDetail(session),
                sampleBook = info.name,
                error = null,
                warnings = session.warnings.toList(),
                stages = stages
            )
        } catch (e: SourceSession.UnsupportedRuleException) {
            stages.add(StageResult(stage, false, "规则用了不支持的语法"))
            return finish(
                source, started, stage, session.lastError, null,
                UiStatus.UNSUPPORTED, stages
            )
        } catch (e: Exception) {
            stages.add(StageResult(stage, false, e.message ?: e.javaClass.simpleName))
            return finish(
                source, started, stage,
                RuleError.NetworkRefused(e.message ?: e.javaClass.simpleName), null,
                stages = stages
            )
        }
    }

    /**
     * 报告正文：四段结果 + 请求数；若有非致命规则错误，追加警告。
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
        forced: UiStatus? = null,
        stages: List<StageResult> = emptyList(),
        forcedStage: String = stage
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
            stuckAt = forcedStage,
            detail = error?.humanMessage ?: "",
            sampleBook = sample,
            error = error,
            stages = stages
        )
    }

    // ---------------- 正文判据 ----------------

    /** 假正文特征。命中即说明"拿到了 HTML 但不是真正文"。 */
    data class FakeContent(val reason: String, val evidence: String, val loginWall: Boolean)

    /**
     * 假正文特征库。
     *
     * 这些都是实测书源里真实出现过的：网站没登录时返回一整页
     * "请登录后阅读"，长度可能上千字，`isReadable` 那种"够长就算过"
     * 的判据会把它判成绿的。
     */
    private val FAKE_PATTERNS: List<Triple<String, String, Boolean>> = listOf(
        // 登录墙（loginWall = true → UI 显示「需登录」而不是「未取到正文」）
        Triple("请先?登录后?(阅读|查看|继续)", "登录墙", true),
        Triple("登录后即可(阅读|查看)", "登录墙", true),
        Triple("(免费|VIP)用户(登录|注册)后", "登录墙", true),
        Triple("您(还)?(未|没有)登录", "登录墙", true),
        Triple("请(先)?注册(并)?登录", "登录墙", true),
        // 人机验证
        Triple("(人机|安全)验证", "人机验证", true),
        Triple("访问(过于|太)频繁", "限流", true),
        Triple("(滑动|拖动).{0,4}验证", "人机验证", true),
        // 转码 / 加载中（不是墙，但也读不到）
        Triple("正文(内容)?(转码|转换)中", "转码中", false),
        Triple("正在(转码|生成|加载)(中|章节内容)", "加载中", false),
        Triple("章节内容(加载|获取)失败", "加载失败", false),
        Triple("(订阅|购买)(本?章|后)(节)?(阅读|查看)", "订阅墙", true),
        Triple("本章(节)?(为|是)?(VIP|付费|收费)章节", "订阅墙", true),
        Triple("余额不足|书币不足", "订阅墙", true)
    )

    private val FAKE_REGEXES by lazy {
        FAKE_PATTERNS.map { (p, reason, login) ->
            Triple(Regex(p), reason, login)
        }
    }

    /**
     * 判定正文是不是假正文。
     *
     * 判据的核心是「这一页的内容**基本上只有**这堵墙」：
     * - 短页面（< 200 字）里命中特征 → 就是墙
     * - 长页面只在**开头 60 字**里找 —— 真正文里"请登录"作为台词出现在
     *   第 500 字很常见，但出现在第一句基本就是墙
     *
     * 注意：**不能**因为"文本太短"就跳过检测。登录墙页面往往只有十几个字，
     * 早期版本先判长度再判墙，结果短墙被判成"正文过短"，
     * 用户看到「未取到正文」，完全无法知道其实是需要登录。
     *
     * @return null 表示没有命中假正文特征
     */
    fun detectFakeContent(text: String): FakeContent? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val head = if (t.length < 200) t else t.take(60)
        for ((regex, reason, login) in FAKE_REGEXES) {
            val m = regex.find(head) ?: continue
            return FakeContent(reason, m.value, login)
        }
        return null
    }

    /**
     * 「合理正文」的判据。
     *
     * ⚠️ 顺序有讲究：**先判假正文，再判长度**。
     * 反过来会让"请先登录后阅读"（12 字）被判成"正文过短"，
     * 丢掉"需登录"这个对用户更有价值的信息。
     *
     * @return null 表示通过；否则返回失败原因
     */
    private fun judgeContent(text: String): FakeContent? {
        val t = text.trim()

        // 1) 假正文（登录墙 / 订阅墙 / 转码中 / 验证码）
        detectFakeContent(t)?.let { return it }

        // 2) 长度
        if (t.length < 50) {
            return FakeContent("正文过短（${t.length} 字）", t.take(40), false)
        }

        // 3) 中文占比过低 → 乱码或只剩导航
        val cjk = t.count { it.code in 0x4E00..0x9FFF }
        if (cjk.toFloat() / t.length < 0.15f) {
            return FakeContent("中文占比过低（疑似乱码）", t.take(40), false)
        }

        // 4) 取到的其实是目录提示
        if (t.contains("目录") && t.length < 200) {
            return FakeContent("取到的是目录提示", t.take(40), false)
        }
        return null
    }

    /** 兼容旧调用点：只要「够长 + 中文占比够 + 不是假正文」。 */
    fun isReadable(text: String): Boolean = judgeContent(text) == null

    private fun isTocNoise(title: String): Boolean {
        val t = title.trim()
        return t.contains("封面") || t.contains("彩插") || t.contains("版权") ||
            t.contains("插图") || t.contains("目录") || t.contains("公告")
    }

    val defaultKeywords = listOf("转生", "魔法", "校园")
}
