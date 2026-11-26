package com.ayanami.reader.core.pixiv

/**
 * Pixiv 小说正文语法解析。
 *
 * ### Pixiv 的四类专有标记
 *
 * | 标记 | 含义 | 处理 |
 * |---|---|---|
 * | `[newpage]` | 分页/换场 | 换成空行（保持段落感），不丢内容 |
 * | `[chapter:标题]` | 章节目录分隔 | 拆成独立章节 |
 * | `[[rb:汉字>假名]]` | 注音（ruby） | 渲染成 `汉字（假名）` |
 * | `[jump:页码]` | 目录跳转 | 丢弃（阅读器自己管章节跳转） |
 *
 * ### 为什么注音要变成「汉字（假名）」
 *
 * 我们不能在纯文本正文里画真 ruby。三种伪方案里：
 * - 只留汉字 → 丢信息，日语读者看不懂读音
 * - 只留假名 → 中文读者完全读不懂
 * - `汉字（假名）` → 两种读者都能读，也是各类阅读器导出 TXT 的通行做法
 *
 * 所以选第三种。`[jump:]` 一定要清掉 —— 留着就是正文里插一段
 * `[jump:2]` 的噪音。
 */
object PixivNovelParser {

    /** 一个拆出来的章节。`title` 为 null 表示「正文开始前的无标题部分」。 */
    data class Section(
        val title: String?,
        val body: String,
        /** 在原文中的起止偏移，便于定位。 */
        val startOffset: Int = 0,
        val endOffset: Int = 0
    )

    /** 解析结果。 */
    data class Parsed(
        /** 若原文没有 `[chapter:]`，这里只有一节且 title 为 null。 */
        val sections: List<Section>,
        /** 去掉所有标记后的完整纯文本。 */
        val plainText: String,
        /** 是否含分页标记（UI 可提示「这是一篇分页小说」）。 */
        val hasNewPage: Boolean,
        /** 注音条数，可用于校验解析是否真的生效。 */
        val rubyCount: Int
    ) {
        /** 是否有显式的章节结构。 */
        val hasChapters: Boolean
            get() = sections.size > 1 || sections.firstOrNull()?.title != null
    }

    private val CHAPTER = Regex("""\[chapter:([^\]]*)]""")
    private val NEW_PAGE = Regex("""\[newpage]""", RegexOption.IGNORE_CASE)
    private val RUBY = Regex("""\[\[rb:([^>\]]+)(?:>([^\]]+))?]]""")
    private val JUMP = Regex("""\[jump:\d+]""")
    /** Pixiv 还有一种 `[[jumpuri:文字>url]]` 外链，也要处理。 */
    private val JUMP_URI = Regex("""\[\[jumpuri:([^>\]]+)>[^\]]*]]""")
    /** `[pixivimage:123456]` 内嵌插画引用。 */
    private val PIXIV_IMAGE = Regex("""\[pixivimage:(\d+)(?:-\d+)?]""")

    /**
     * 解析整篇正文。
     *
     * @param raw Pixiv API 返回的 `content` 字段原文
     */
    fun parse(raw: String): Parsed {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) {
            return Parsed(listOf(Section(null, "")), "", false, 0)
        }

        val hasNewPage = NEW_PAGE.containsMatchIn(text)
        val rubyCount = RUBY.findAll(text).count()

        // ---- 1) 按 [chapter:] 切段 ----
        val chapterMatches = CHAPTER.findAll(text).toList()
        val rawSections: List<Section> = if (chapterMatches.isEmpty()) {
            listOf(Section(null, text, 0, text.length))
        } else {
            val out = ArrayList<Section>()
            // 第一个 [chapter:] 之前的内容（常见是空白或序言）
            val firstStart = chapterMatches.first().range.first
            if (firstStart > 0) {
                val head = text.substring(0, firstStart)
                if (head.isNotBlank() && stripMarkers(head).isNotBlank()) {
                    out.add(Section(null, head, 0, firstStart))
                }
            }
            chapterMatches.forEachIndexed { i, m ->
                val title = m.groupValues[1].trim()
                val bodyStart = m.range.last + 1
                val bodyEnd = chapterMatches.getOrNull(i + 1)?.range?.first ?: text.length
                out.add(Section(title, text.substring(bodyStart, bodyEnd), bodyStart, bodyEnd))
            }
            out
        }

        // ---- 2) 每段内部把标记转成可读文本 ----
        val cleaned = rawSections.map { it.copy(body = render(it.body).trim()) }
            .filter { it.title != null || it.body.isNotBlank() }

        return Parsed(
            sections = cleaned.ifEmpty { listOf(Section(null, "")) },
            plainText = render(text).trim(),
            hasNewPage = hasNewPage,
            rubyCount = rubyCount
        )
    }

    /** 只把标记渲染成纯文本，不拆章。 */
    fun render(raw: String): String {
        var s = raw

        // 注音：[[rb:汉字>かんじ]] → 汉字（かんじ）
        // 没有假名部分时（[[rb:汉字]]）只留汉字
        s = RUBY.replace(s) { m ->
            val base = m.groupValues[1]
            val ruby = m.groupValues[2]
            if (ruby.isBlank()) base else "$base（$ruby）"
        }

        // 外链：[[jumpuri:文字>url]] → 文字
        s = JUMP_URI.replace(s) { it.groupValues[1] }

        // 内嵌插画引用：转成一个可读占位，而不是留一串 ID
        s = PIXIV_IMAGE.replace(s) { m -> "［插图 ${m.groupValues[1]}］" }

        // 分页 → 空行（Pixiv 的 newpage 就是「换场/换页」）
        s = NEW_PAGE.replace(s, "\n\n")

        // 目录跳转 → 删除
        s = JUMP.replace(s, "")

        // 折叠过多空行
        s = s.replace(Regex("""\n{3,}"""), "\n\n")

        return s
    }

    /** 去标记但不做换行折叠，用于判断「这段到底有没有实际内容」。 */
    private fun stripMarkers(raw: String): String =
        JUMP.replace(NEW_PAGE.replace(CHAPTER.replace(raw, ""), "\n"), "").trim()
}
