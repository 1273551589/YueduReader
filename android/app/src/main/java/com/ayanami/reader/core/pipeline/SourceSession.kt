package com.ayanami.reader.core.pipeline

import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookChapter
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.model.TextChapter
import com.ayanami.reader.core.net.FetchResult
import com.ayanami.reader.core.net.HttpFetcher
import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.LenientJson
import com.ayanami.reader.core.rule.RuleAnalyzer
import com.ayanami.reader.core.rule.RuleError
import com.ayanami.reader.core.rule.JsRuntime
import com.ayanami.reader.core.rule.RuleType
import com.ayanami.reader.core.rule.UrlHelper

/**
 * 一次书源会话：搜索 → 详情 → 目录 → 正文。
 *
 * **实例粒度是本类最重要的一条纪律**（方案 §4.5）：
 * 四个阶段各自 new 一个 [AnalyzeRule]，正文阶段内部复用。
 * 理由：`isRegex` 一旦被 `getElement(":...")` 置 true 就永不复位，
 * 复用一个实例会让后续所有 `getString` 静默走正则模式。
 *
 * 变量表 [sharedVars] 是唯一的跨阶段状态，`@put/@get` 靠它累积。
 */
class SourceSession(
    val source: BookSource,
    /** 体检模式：强制礼貌间隔、不重试 4xx。 */
    val polite: Boolean = false
) {
    /** `@put` 写在搜索阶段、正文阶段要能读到 —— 跨阶段共享，但每源独立。 */
    private val sharedVars = LinkedHashMap<String, String>()

    private val fetcher = HttpFetcher

    var lastError: RuleError? = null

    /** 书源自带 header（JSON 串），每个请求都要带上 —— 少带会被判成爬虫。 */
    private val sourceHeaders: Map<String, String> by lazy {
        val raw = source.header
        if (raw.isNullOrBlank()) emptyMap()
        else LenientJson.parseStringMap(raw) ?: emptyMap()
    }

    fun putSharedVar(key: String, value: String) {
        sharedVars[key] = value
    }

    fun getSharedVar(key: String): String? = sharedVars[key]

    private fun mergedHeaders(extra: Map<String, String>? = null): Map<String, String>? {
        if (sourceHeaders.isEmpty() && extra == null) return null
        val m = HashMap<String, String>(sourceHeaders)
        extra?.forEach { (k, v) -> m[k] = v }
        return m
    }

    fun reset() {
        sharedVars.clear()
        lastError = null
        warnings.clear()
        jsLogs.clear()
    }

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String, page: Int = 1): List<SearchBook> {
        val searchUrl = source.searchUrl
        if (searchUrl.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置搜索")
            return emptyList()
        }
        val rule = source.ruleSearch
        if (rule.bookList.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置搜索规则")
            return emptyList()
        }

        currentStage = "search"
        val urlTemplate = searchUrl
            .replace("{{key}}", encode(keyword))
            .replace("{{page}}", page.toString())
        val (url, option) = splitUrlOption(urlTemplate)

        val res = fetcher.fetch(
            url = url,
            method = option?.method ?: "GET",
            body = option?.body?.toString(),
            headers = mergedHeaders(option?.headers),
            charsetName = option?.charset
        )
        val body = when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                lastError = res.error
                return emptyList()
            }
        }

        val analyze = attachJs(newAnalyzer(body, url))

        // `init` 之类的前置动作：首版不做，但记录显式不支持
        val listRule: String = rule.bookList ?: ""
        val elements = analyze.getElements(stripForList(listRule))
        if (elements.isEmpty()) {
            lastError = RuleError.SelectorEmpty("search.bookList")
            return emptyList()
        }

        val out = ArrayList<SearchBook>()
        // 只有规则里真出现 @js 才建引擎 —— 26 条搜索结果不值得建 26 个 Rhino
        val searchRules = listOf(
            rule.name, rule.author, rule.kind, rule.intro,
            rule.lastChapter, rule.updateTime, rule.coverUrl,
            rule.wordCount, rule.bookUrl
        )
        val needJs = searchRules.any { needsJs(it) }

        for (el in elements) {
            // 每条再用独立实例，避免条目间状态串
            val per = AnalyzeRule().apply {
                bindSharedVars(sharedVars)
                setContent(el, url)
                onRuleError = { e -> noteWarning(currentStage, e) }
            }
            if (needJs) attachJsForElement(per, el)
            val name = per.getString(rule.name ?: "@text")?.trim().orEmpty()
            if (name.isEmpty()) continue
            out.add(
                SearchBook(
                    name = name,
                    author = per.getString(rule.author)?.trim().orEmpty(),
                    kind = per.getString(rule.kind)?.takeIf { it.isNotBlank() },
                    intro = per.getString(rule.intro)?.takeIf { it.isNotBlank() },
                    lastChapter = per.getString(rule.lastChapter)?.takeIf { it.isNotBlank() },
                    updateTime = per.getString(rule.updateTime)?.takeIf { it.isNotBlank() },
                    coverUrl = per.getString(rule.coverUrl)?.takeIf { it.isNotBlank() },
                    wordCount = per.getString(rule.wordCount)?.takeIf { it.isNotBlank() },
                    bookUrl = per.getString(rule.bookUrl ?: "@href")?.trim().orEmpty(),
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName
                )
            )
        }
        if (out.isEmpty()) lastError = RuleError.SelectorEmpty("search.items")
        return out
    }

    // ---------------- 详情 ----------------

    suspend fun bookInfo(bookUrl: String): Book? {
        val rule = source.ruleBookInfo
        val url = rule.init?.let { _ ->
            // `init` 是规则串而非 URL；首版不做二次请求
            bookUrl
        } ?: bookUrl

        currentStage = "bookInfo"
        val res = fetcher.fetch(url, headers = mergedHeaders())
        val body = when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                lastError = res.error
                return null
            }
        }

        val analyze = attachJs(newAnalyzer(body, url))

        val name = analyze.getString(rule.name ?: "h1@text")?.trim().orEmpty()
        val tocUrl = analyze.getString(rule.tocUrl)?.trim().orEmpty()
        return Book(
            name = name,
            author = analyze.getString(rule.author)?.trim().orEmpty(),
            intro = analyze.getString(rule.intro)?.takeIf { it.isNotBlank() },
            kind = analyze.getString(rule.kind)?.takeIf { it.isNotBlank() },
            lastChapter = analyze.getString(rule.lastChapter)?.takeIf { it.isNotBlank() },
            updateTime = analyze.getString(rule.updateTime)?.takeIf { it.isNotBlank() },
            coverUrl = analyze.getString(rule.coverUrl)?.takeIf { it.isNotBlank() },
            wordCount = analyze.getString(rule.wordCount)?.takeIf { it.isNotBlank() },
            tocUrl = tocUrl.ifBlank { url },
            bookUrl = url,
            origin = source.bookSourceUrl,
            originName = source.bookSourceName
        ).also {
            if (it.name.isBlank()) lastError = RuleError.SelectorEmpty("bookInfo.name")
        }
    }

    // ---------------- 目录 ----------------

    suspend fun toc(book: Book): List<BookChapter> {
        val rule = source.ruleToc
        if (rule.chapterList.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置目录规则")
            return emptyList()
        }

        currentStage = "toc"
        val url = book.tocUrl.ifBlank { book.bookUrl }
        val res = fetcher.fetch(url, headers = mergedHeaders())
        val body = when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                lastError = res.error
                return emptyList()
            }
        }

        val chapterListRule: String = rule.chapterList ?: ""
        val out = ArrayList<BookChapter>()
        val visitedPages = LinkedHashSet<String>()

        // 目录分页：`nextTocUrl` 给出下一页地址。
        // 每轮都重新抓（首轮用已经拿到的 body，避免重复请求）。
        var pageUrl: String? = url
        var firstBody: String? = body

        while (pageUrl != null && visitedPages.size < MAX_TOC_PAGES) {
            if (!visitedPages.add(pageUrl)) break

            val currentBody = firstBody ?: run {
                val r = fetcher.fetch(pageUrl!!, headers = mergedHeaders())
                if (r is FetchResult.Ok) r.body else {
                    if (out.isEmpty()) lastError = (r as FetchResult.Err).error
                    break
                }
            }
            firstBody = null

            val analyze = attachJs(newAnalyzer(currentBody, pageUrl))

            // 下一页规则要在**当前页**上求值，不能等循环变量被改写
            val nextRaw: String? = rule.nextTocUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { r -> analyze.getString(r)?.trim().orEmpty().ifEmpty { null } }

            val elements = analyze.getElements(stripForList(chapterListRule))
            if (elements.isEmpty() && out.isEmpty()) {
                lastError = RuleError.SelectorEmpty("toc.chapterList")
                return emptyList()
            }

            // 只有规则里真的出现 @js / <js> 才建 JS 引擎。
            // 否则 421 章目录会白建 421 个 Rhino 运行时（实测体检从 2.3s 涨到 9.6s）。
            val needJs = needsJs(rule.chapterName) || needsJs(rule.chapterUrl)

            for (el in elements) {
                val per = AnalyzeRule().apply {
                    bindSharedVars(sharedVars)
                    setContent(el, pageUrl)
                    onRuleError = { e -> noteWarning(currentStage, e) }
                }
                if (needJs) attachJsForElement(per, el)
                val title = per.getString(rule.chapterName ?: "@text")?.trim().orEmpty()
                val chapterUrl = per.getString(rule.chapterUrl ?: "@href")?.trim().orEmpty()
                if (title.isEmpty() || chapterUrl.isEmpty()) continue
                out.add(BookChapter(title = title, url = chapterUrl, index = out.size))
            }

            pageUrl = nextRaw?.let { UrlHelper.resolve(pageUrl, it) }
        }

        if (out.isEmpty()) lastError = RuleError.SelectorEmpty("toc.items")
        return out
    }

    // ---------------- 正文 ----------------

    /**
     * @param maxPages 正文分页上限。体检传 1–2（只要判断"能读到"），
     *   正常阅读用默认的 [MAX_CONTENT_PAGES]（要读完整章）。
     */
    suspend fun content(chapter: BookChapter, maxPages: Int = MAX_CONTENT_PAGES): TextChapter? {
        val rule = source.ruleContent
        if (rule.content.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置正文规则")
            return null
        }

        currentStage = "content"
        val res = fetcher.fetch(chapter.url, headers = mergedHeaders())
        val body = when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                lastError = res.error
                return null
            }
        }

        // 正文阶段内复用同一实例（同一章的分页合并、图片抽取都靠它）
        val analyze = attachJs(newAnalyzer(body, chapter.url))

        val text = analyze.getString(rule.content)?.trim().orEmpty()
        if (text.isEmpty()) {
            analyze.lastError?.let { lastError = it }
                ?: run { lastError = RuleError.SelectorEmpty("content.content") }
            return null
        }

        // ---- 正文分页 ----
        // 很多站的正文是分页的，不合并就只能读到第一页 —— 这是"读不完一本书"的直接原因。
        val pieces = ArrayList<String>().apply { add(text) }
        val visited = LinkedHashSet<String>().apply { add(chapter.url) }
        var nextUrl: String? = rule.nextContentUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { analyze.getString(it)?.trim().orEmpty().ifEmpty { null } }
            ?.let { UrlHelper.resolve(chapter.url, it) }

        var pageCount = 1
        while (nextUrl != null && pageCount < maxPages) {
            if (!visited.add(nextUrl)) break

            val r = fetcher.fetch(nextUrl, headers = mergedHeaders())
            if (r !is FetchResult.Ok) break

            val pageAnalyze = attachJs(newAnalyzer(r.body, nextUrl))
            val pageText = pageAnalyze.getString(rule.content)?.trim().orEmpty()
            if (pageText.isEmpty()) break
            pieces.add(pageText)
            pageCount++

            nextUrl = rule.nextContentUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { pageAnalyze.getString(it)?.trim().orEmpty().ifEmpty { null } }
                ?.let { UrlHelper.resolve(nextUrl!!, it) }
        }

        val merged = pieces.joinToString("\n")

        // 正文内的图（图片型源与插图）
        val images = Regex("""<img[^>]+src=['"]([^'"]+)['"]""")
            .findAll(merged)
            .map { UrlHelper.resolve(chapter.url, it.groupValues[1]) }
            .distinct()
            .toList()

        val clean = merged
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace(Regex("""</p>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

        if (clean.isEmpty() && images.isEmpty()) {
            lastError = RuleError.SelectorEmpty("content.text")
            return null
        }

        return TextChapter(
            title = chapter.title,
            url = chapter.url,
            text = clean,
            images = images
        )
    }

    // ---------------- helpers ----------------

    /** `searchUrl`/`tocUrl` 可能是 `url,{json}`。 */
    private fun splitUrlOption(raw: String): Pair<String, com.ayanami.reader.core.rule.UrlOption?> {
        val trimmed = raw.trim()
        val comma = findTopLevelComma(trimmed)
        if (comma < 0) return trimmed to null
        val url = trimmed.substring(0, comma)
        val json = trimmed.substring(comma + 1)
        return url to LenientJson.parseUrlOption(json)
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

    /**
     * 列表型规则可能带 `@` 属性后缀，`getElements` 只吃选择器部分；
     * 另外首版不支持 `@js`，命中要显式报 UnsupportedApi。
     */
    private fun stripForList(ruleRaw: String): String {
        val parsed = RuleAnalyzer.parsePrefix(ruleRaw)
        if (parsed.type == RuleType.Js) {
            lastError = RuleError.UnsupportedApi("@js 规则（首版不支持脚本）")
            throw UnsupportedRuleException()
        }
        // 去掉 `##正则##` 尾巴与 `@attr`
        val noRegex = parsed.rule.substringBefore("##").trim()
        val at = noRegex.indexOf('@')
        return if (at > 0) noRegex.substring(0, at).trim() else noRegex
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * 每阶段一个全新的 [AnalyzeRule]，并配一个属于它的 [JsRuntime]。
     *
     * 为什么 JS 运行时也要每阶段新建：`java.put` 的变量本该跨阶段存活，
     * 那部分走 [sharedVars]；但编译缓存、`lastUnsupported`、超时计数是**实例状态**，
     * 跨阶段复用会让"上一阶段的脚本报错"污染这一阶段的判断。
     */
    private fun newAnalyzer(content: String, url: String): AnalyzeRule {
        val a = AnalyzeRule()
        a.bindSharedVars(sharedVars)
        a.setContent(content, url)
        // 出错当下就记，不能事后看 lastError（会被后续成功冲掉）
        a.onRuleError = { e -> noteWarning(currentStage, e) }
        return a
    }

    /** 当前正在跑哪个阶段，供警告归类。 */
    @Volatile
    var currentStage: String = ""

    /** 需要 JS 的阶段用这个：额外接一个 bridge（依赖已建好的 analyzer）。 */
    /**
     * 阶段级 `@js` 运行时。
     *
     * 注意 `currentContent = { null }` 是**对的**，不是 bug：
     * `JsRuntime` 里取值顺序是 `content ?: bridge.currentContent()`，
     * 而 `AnalyzeRule` 已经把 HTML 存进自己的 `element/document` 了，
     * `java.getString` 走的是那条路径。这里只在**条目级**（[attachJsForElement]）
     * 才需要给出具体的 Element。
     *
     * （v0.3.3 曾误以为这里是缺陷并加了冗余参数；注入回归验证后确认它不改变行为，
     * 已删掉，避免后人以为那参数在起作用。）
     */
    private fun attachJs(analyzer: AnalyzeRule): AnalyzeRule {
        val bridge = HttpJsBridge(
            session = this,
            currentContent = { null },
            analyzerProvider = { analyzer }
        )
        bridge.logSink = { msg -> jsLogs.add(msg) }
        analyzer.jsRuntime = JsRuntime(bridge)
        return analyzer
    }

    /** 这条规则串是否需要 JS 运行时。 */
    private fun needsJs(rule: String?): Boolean {
        if (rule.isNullOrBlank()) return false
        return rule.contains("@js:", ignoreCase = true) || rule.contains("<js>", ignoreCase = true)
    }

    /** 给某个条目元素配 JS 运行时：`java.getString` 要落在这一条上。 */
    private fun attachJsForElement(analyzer: AnalyzeRule, el: org.jsoup.nodes.Element) {
        val bridge = HttpJsBridge(
            session = this,
            currentContent = { el },
            analyzerProvider = { analyzer }
        )
        bridge.logSink = { msg -> jsLogs.add(msg) }
        analyzer.jsRuntime = JsRuntime(bridge)
    }

    /** `java.log` 的输出，方便在体检报告里显示。 */
    val jsLogs = ArrayList<String>()

    /**
     * 拿到的**非致命**规则错误。
     *
     * 场景：`ruleSearch.name` 的正则写错了，但 `getString` 会返回原文，
     * 于是"名字有值"、整条流程能跑通 → 状态仍是「抽样可读」，
     * 用户完全不知道书名其实是错的。
     *
     * 这里把过程中的错误收集起来，体检报告里作为「警告」显示。
     */
    val warnings = ArrayList<Pair<String, RuleError>>()

    fun noteWarning(stage: String, e: RuleError?) {
        if (e == null) return
        // 同一阶段同类错误只记一次，避免 421 章刷屏
        if (warnings.any { it.first == stage && it.second.javaClass == e.javaClass }) return
        warnings.add(stage to e)
    }

    class UnsupportedRuleException : RuntimeException()

    private companion object {
        /** 目录分页上限：防止规则写错导致无限抓。 */
        const val MAX_TOC_PAGES = 50

        /** 正文分页上限。 */
        const val MAX_CONTENT_PAGES = 20
    }
}
