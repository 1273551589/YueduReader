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
import com.ayanami.reader.core.rule.RuleSteps
import com.ayanami.reader.core.rule.RuleType
import com.ayanami.reader.core.rule.UrlHelper
import com.ayanami.reader.core.rule.UrlOption
import com.ayanami.reader.core.rule.UrlTemplate

/**
 * 一次书源会话：搜索 → 详情 → 目录 → 正文。
 *
 * **实例粒度是本类最重要的一条纪律**（方案 §4.5）：
 * 四个阶段各自 new 一个 [AnalyzeRule]，正文阶段内部复用。
 * 理由：规则缓存与错误状态是实例字段，复用一个实例会让
 * 上一阶段的失败污染这一阶段的判断。
 *
 * 变量表 [sharedVars] 是唯一的跨阶段状态，`@put/@get` 靠它累积。
 *
 * ### v0.4.0 修复的请求层缺陷
 *
 * - 相对 `searchUrl` / `exploreUrl` / `tocUrl` 一律通过 [UrlTemplate]
 *   与 `bookSourceUrl` 拼成绝对地址（书源写 `/s.php` 时以前直接抛 URL 异常）
 * - POST body 里的 `{{key}}` / `{{page}}` 真做替换（以前原样发出去 → 搜空）
 * - 请求参数按书源声明的 `charset` 编码（GBK 站以前用 UTF-8 编码 → 乱码）
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

    /** 书源基地址：所有相对地址都相对它解析。 */
    private val baseUrl: String
        get() = source.bookSourceUrl.trim().trimEnd('/')

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

    // ---------------- 请求构建 ----------------

    /** 一次请求的完整描述 —— 把 URL/Body/字符集三件事收口。 */
    private data class Request(
        val url: String,
        val method: String,
        val body: String?,
        val charset: String?
    )

    /**
     * 从模板构建请求。
     *
     * @param template  书源里的原始串，可能是 `url` 或 `url,{json}`
     * @param vars      变量表（`key` / `page`，以及 `@put` 写入的）
     * @param resolveBase 相对地址的基地址；null 表示用 `bookSourceUrl`
     */
    private fun buildRequest(
        template: String,
        vars: Map<String, String>,
        resolveBase: String? = null
    ): Request {
        val (rawUrl, rawOption) = UrlTemplate.splitUrlOption(template)
        val option: UrlOption? = rawOption?.let { LenientJson.parseUrlOption(it) }
        val charset = option?.charset
        val base = resolveBase ?: baseUrl

        val url = UrlTemplate.render(
            template = rawUrl,
            vars = vars,
            charset = charset,
            baseUrl = base,
            encode = true
        )

        val method = (option?.method ?: "GET").uppercase()

        // body 可能是字符串，也可能是对象/数组 —— Legado 里对象要序列化成 JSON
        val rawBody: String? = when (val b = option?.body) {
            null -> null
            is String -> b
            else -> LenientJson.lenient.toJson(b)
        }

        val body = rawBody?.let { b ->
            UrlTemplate.render(
                template = b,
                vars = vars,
                charset = charset,
                baseUrl = "",
                // form 编码的 body 要编；JSON body 不能编，否则引号变 %22
                encode = !b.trimStart().startsWith("{")
            )
        }

        return Request(url, method, body, charset)
    }

    /** 发一次请求，失败时把错误落到 [lastError]。 */
    private suspend fun execute(req: Request, extraHeaders: Map<String, String>? = null): String? {
        val res = fetcher.fetch(
            url = req.url,
            method = req.method,
            body = req.body,
            headers = mergedHeaders(extraHeaders),
            charsetName = req.charset
        )
        return when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                lastError = res.error
                null
            }
        }
    }

    // ---------------- 解析搜索/发现页 ----------------

    /**
     * 搜索。
     *
     * @param keyword 关键词
     * @param page    页码（`{{page}}`）
     */
    suspend fun search(keyword: String, page: Int = 1): List<SearchBook> {
        val searchUrl = source.searchUrl
        if (searchUrl.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置搜索")
            return emptyList()
        }
        val rule = source.ruleSearch
        val listRule = rule.bookList
        if (listRule.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置搜索规则")
            return emptyList()
        }

        currentStage = "search"
        val req = buildRequest(
            searchUrl,
            vars = mapOf("key" to keyword, "page" to page.toString()),
            resolveBase = baseUrl
        )
        val body = execute(req) ?: return emptyList()

        return parseList(
            body = body,
            pageUrl = req.url,
            listRule = listRule,
            name = rule.name,
            author = rule.author,
            kind = rule.kind,
            intro = rule.intro,
            lastChapter = rule.lastChapter,
            updateTime = rule.updateTime,
            coverUrl = rule.coverUrl,
            wordCount = rule.wordCount,
            bookUrl = rule.bookUrl
        )
    }

    /**
     * 发现页（书城）。
     *
     * `exploreUrl` 是 Legado 的特殊字段，格式是
     * `分类名::地址` 用 `\n` 或 `&&` 分隔的多条；不带 `::` 时整串当一条。
     *
     * @param exploreUrl 单条发现页地址（分类由调用方拆好传进来）
     */
    suspend fun explore(exploreUrl: String, page: Int = 1): List<SearchBook> {
        val rule = source.ruleExplore
        val listRule = rule.bookList
        if (listRule.isNullOrBlank()) {
            lastError = RuleError.SkippedStage("书源没有配置发现规则")
            return emptyList()
        }

        currentStage = "explore"
        val req = buildRequest(
            exploreUrl,
            vars = mapOf("page" to page.toString(), "key" to ""),
            resolveBase = baseUrl
        )
        val body = execute(req) ?: return emptyList()

        return parseList(
            body = body,
            pageUrl = req.url,
            listRule = listRule,
            name = rule.name,
            author = rule.author,
            kind = rule.kind,
            intro = rule.intro,
            lastChapter = rule.lastChapter,
            updateTime = rule.updateTime,
            coverUrl = rule.coverUrl,
            wordCount = rule.wordCount,
            bookUrl = rule.bookUrl
        )
    }

    /**
     * 拆 `exploreUrl` 成 `分类名 to 地址` 列表。
     *
     * Legado 的写法：
     * ```
     * 玄幻::https://a.com/list/xhuan.html
     * 都市::https://a.com/list/dushi.html
     * ```
     * 也支持 `&&` 分隔、以及单条不带分类名的裸地址。
     */
    fun exploreCategories(): List<Pair<String, String>> {
        val raw = source.exploreUrl
        if (raw.isNullOrBlank()) return emptyList()

        // 先按 `&&` 切，再按换行切 —— 顺序不能反，否则 `a&&b` 会被拆成
        // 三个标签却只认出两条，分类数对不上
        val entries = raw.split("&&")
            .flatMap { it.split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val out = ArrayList<Pair<String, String>>()
        for (line in entries) {
            val idx = line.indexOf("::")
            when {
                idx > 0 -> {
                    val name = line.substring(0, idx).trim()
                    val url = line.substring(idx + 2).trim()
                    if (url.isNotEmpty()) out.add(name to url)
                }
                line.startsWith("http") || line.startsWith("/") -> out.add("全部" to line)
                else -> Unit   // 认不出的行跳过，不污染分类列表
            }
        }
        return out
    }

    /** 搜索 / 发现共用的列表解析。 */
    private fun parseList(
        body: String,
        pageUrl: String,
        listRule: String,
        name: String?,
        author: String?,
        kind: String?,
        intro: String?,
        lastChapter: String?,
        updateTime: String?,
        coverUrl: String?,
        wordCount: String?,
        bookUrl: String?
    ): List<SearchBook> {
        val analyze = attachJs(newAnalyzer(body, pageUrl))

        val elements = analyze.getElements(stripForList(listRule))
        if (elements.isEmpty()) {
            lastError = RuleError.SelectorEmpty("$currentStage.bookList")
            return emptyList()
        }

        val out = ArrayList<SearchBook>()
        // 只有规则里真出现 @js 才建引擎 —— 26 条搜索结果不值得建 26 个 Rhino
        val searchRules = listOf(
            name, author, kind, intro, lastChapter, updateTime, coverUrl, wordCount, bookUrl
        )
        val needJs = searchRules.any { needsJs(it) }

        for (el in elements) {
            // 每条再用独立实例，避免条目间状态串
            val per = AnalyzeRule().apply {
                bindSharedVars(sharedVars)
                setContent(el, pageUrl)
                onRuleError = { e -> noteWarning(currentStage, e) }
            }
            if (needJs) attachJsForElement(per, el)
            val bookName = per.getString(name ?: "tag.a@text")?.trim().orEmpty()
            if (bookName.isEmpty()) continue
            val url = per.getString(bookUrl ?: "tag.a@href")?.trim().orEmpty()
            if (url.isEmpty()) continue
            out.add(
                SearchBook(
                    name = bookName,
                    author = per.getString(author)?.trim().orEmpty(),
                    kind = per.getString(kind)?.takeIf { it.isNotBlank() },
                    intro = per.getString(intro)?.takeIf { it.isNotBlank() },
                    lastChapter = per.getString(lastChapter)?.takeIf { it.isNotBlank() },
                    updateTime = per.getString(updateTime)?.takeIf { it.isNotBlank() },
                    coverUrl = per.getString(coverUrl)?.takeIf { it.isNotBlank() },
                    wordCount = per.getString(wordCount)?.takeIf { it.isNotBlank() },
                    bookUrl = url,
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName
                )
            )
        }
        if (out.isEmpty()) lastError = RuleError.SelectorEmpty("$currentStage.items")
        return out
    }

    // ---------------- 详情 ----------------

    suspend fun bookInfo(bookUrl: String): Book? {
        val rule = source.ruleBookInfo

        currentStage = "bookInfo"
        val url = UrlTemplate.resolveIfRelative(baseUrl, bookUrl)
        val body = execute(Request(url, "GET", null, null)) ?: return null

        val analyze = attachJs(newAnalyzer(body, url))

        val name = analyze.getString(rule.name ?: "tag.h1@text")?.trim().orEmpty()
        // tocUrl 可能是相对地址 —— 不拼就会拿 "/list/1.html" 去发请求
        val tocUrl = analyze.getString(rule.tocUrl)?.trim().orEmpty()
            .let { if (it.isBlank()) url else UrlTemplate.resolveIfRelative(url, it) }

        return Book(
            name = name,
            author = analyze.getString(rule.author)?.trim().orEmpty(),
            intro = analyze.getString(rule.intro)?.takeIf { it.isNotBlank() },
            kind = analyze.getString(rule.kind)?.takeIf { it.isNotBlank() },
            lastChapter = analyze.getString(rule.lastChapter)?.takeIf { it.isNotBlank() },
            updateTime = analyze.getString(rule.updateTime)?.takeIf { it.isNotBlank() },
            coverUrl = analyze.getString(rule.coverUrl)?.takeIf { it.isNotBlank() },
            wordCount = analyze.getString(rule.wordCount)?.takeIf { it.isNotBlank() },
            tocUrl = tocUrl,
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
        val startUrl = UrlTemplate.resolveIfRelative(
            baseUrl,
            book.tocUrl.ifBlank { book.bookUrl }
        )
        val out = ArrayList<BookChapter>()
        val visitedPages = LinkedHashSet<String>()

        var pageUrl: String? = startUrl
        var firstBody: String? = execute(Request(startUrl, "GET", null, null))
        if (firstBody == null) return emptyList()

        while (pageUrl != null && visitedPages.size < MAX_TOC_PAGES) {
            if (!visitedPages.add(pageUrl)) break

            val currentBody = firstBody ?: run {
                val r = HttpFetcher.fetch(pageUrl!!, headers = mergedHeaders())
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

            val elements = try {
                analyze.getElements(stripForList(rule.chapterList ?: ""))
            } catch (e: UnsupportedRuleException) {
                return emptyList()
            }
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
                val title = per.getString(rule.chapterName ?: "tag.a@text")?.trim().orEmpty()
                val chapterUrlRaw = per.getString(rule.chapterUrl ?: "tag.a@href")?.trim().orEmpty()
                if (title.isEmpty() || chapterUrlRaw.isEmpty()) continue
                val chapterUrl = UrlTemplate.resolveIfRelative(pageUrl, chapterUrlRaw)
                out.add(BookChapter(title = title, url = chapterUrl, index = out.size))
            }

            pageUrl = nextRaw?.let { UrlTemplate.resolveIfRelative(pageUrl, it) }
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
        val chapterUrl = UrlTemplate.resolveIfRelative(baseUrl, chapter.url)
        val body = execute(Request(chapterUrl, "GET", null, null)) ?: return null

        // 正文阶段内复用同一实例（同一章的分页合并、图片抽取都靠它）
        val analyze = attachJs(newAnalyzer(body, chapterUrl))

        val text = analyze.getString(rule.content)?.trim().orEmpty()
        if (text.isEmpty()) {
            analyze.lastError?.let { lastError = it }
                ?: run { lastError = RuleError.SelectorEmpty("content.content") }
            return null
        }

        // ---- 正文分页 ----
        // 很多站的正文是分页的，不合并就只能读到第一页 —— 这是"读不完一本书"的直接原因。
        val pieces = ArrayList<String>().apply { add(text) }
        val visited = LinkedHashSet<String>().apply { add(chapterUrl) }
        var nextUrl: String? = rule.nextContentUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { analyze.getString(it)?.trim().orEmpty().ifEmpty { null } }
            ?.let { UrlTemplate.resolveIfRelative(chapterUrl, it) }

        var pageCount = 1
        while (nextUrl != null && pageCount < maxPages) {
            if (!visited.add(nextUrl)) break

            val r = HttpFetcher.fetch(nextUrl, headers = mergedHeaders())
            if (r !is FetchResult.Ok) break

            val pageAnalyze = attachJs(newAnalyzer(r.body, nextUrl))
            val pageText = pageAnalyze.getString(rule.content)?.trim().orEmpty()
            if (pageText.isEmpty()) break
            pieces.add(pageText)
            pageCount++

            val cur = nextUrl
            nextUrl = rule.nextContentUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { pageAnalyze.getString(it)?.trim().orEmpty().ifEmpty { null } }
                ?.let { UrlTemplate.resolveIfRelative(cur, it) }
        }

        val merged = pieces.joinToString("\n")

        // 正文内的图（图片型源与插图）
        val images = Regex("""<img[^>]+src=['"]([^'"]+)['"]""")
            .findAll(merged)
            .map { UrlTemplate.resolveIfRelative(chapterUrl, it.groupValues[1]) }
            .distinct()
            .toList()

        val clean = cleanHtml(merged)

        if (clean.isEmpty() && images.isEmpty()) {
            lastError = RuleError.SelectorEmpty("content.text")
            return null
        }

        return TextChapter(
            title = chapter.title,
            url = chapterUrl,
            text = clean,
            images = images
        )
    }

    private fun cleanHtml(html: String): String = html
        .replace(Regex("""<br\s*/?>"""), "\n")
        .replace(Regex("""</p>"""), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    // ---------------- helpers ----------------

    /**
     * 列表型规则只吃选择器部分。
     *
     * v0.4.0 起统一走 [RuleSteps.selectorSteps] —— 之前用 `indexOf('@')`
     * 只切第一段，`class.odd.0@tag.a@href` 的列表规则会被截成
     * `class.odd.0`，丢掉整段下钻。
     */
    private fun stripForList(ruleRaw: String): String {
        val parsed = RuleAnalyzer.parsePrefix(ruleRaw)
        if (parsed.type == RuleType.Js) {
            lastError = RuleError.UnsupportedApi("@js 规则（列表选择器不支持脚本）")
            throw UnsupportedRuleException()
        }
        // 去掉 `##正则##` 尾巴（列表层的正则在逐条取值时应用）
        val noRegex = parsed.rule.substringBefore("##").trim()
        val steps = RuleSteps.splitSteps(noRegex).map { it.trim() }.filter { it.isNotEmpty() }
        val sel = RuleSteps.selectorSteps(steps)
        return sel.joinToString("@")
    }

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

    /**
     * 阶段级 `@js` 运行时。
     *
     * 注意 `currentContent = { null }` 是**对的**，不是 bug：
     * `JsRuntime` 里取值顺序是 `content ?: bridge.currentContent()`，
     * 而 `AnalyzeRule` 已经把 HTML 存进自己的 `element/document` 了，
     * `java.getString` 走的是那条路径。这里只在**条目级**（[attachJsForElement]）
     * 才需要给出具体的 Element。
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
        return rule.contains("@js:", ignoreCase = true) ||
            rule.contains("<js>", ignoreCase = true)
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
