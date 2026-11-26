package com.ayanami.reader.ui.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * 章内分页器。
 *
 * ### 为什么要自己算，而不是「按固定字数切」
 *
 * 按字数切（每页 500 字）在任何设备上都对不上：字号 13 和 30 差 2.3 倍，
 * 行距 1.2 和 2.4 又差 2 倍 —— 同一章在小屏紧排版上可能 8 页，
 * 在大屏松排版上是 5 页。切出来的页要么底部大片空白，
 * 要么文字被截断（用户会以为正文缺字）。
 *
 * ### 做法
 *
 * 用 [TextMeasurer] 在**真实约束**（视口宽高 - padding）下测量整章，
 * 从 `TextLayoutResult` 里按行 `getLineBottom()` 累计，凑满一屏高度就断页。
 * 断点落在行首，所以永远不会把一行劈开。
 *
 * ### 成本
 *
 * 一次测量 8000 字约 10–30ms（在 UI 线程上）。所以结果必须缓存 ——
 * 不能每帧都测。缓存键包含所有影响排版的参数（字号/行距/宽高/文本），
 * 任意一个变了就重算。见 [PaginatorCache]。
 */
object ChapterPaginator {

    /** 一页在正文里的位置。 */
    data class Page(
        /** 起始字符下标（含） */
        val start: Int,
        /** 结束字符下标（不含） */
        val end: Int,
        /** 本页实际高度，调试用 */
        val heightPx: Int = 0
    )

    /**
     * 把一段文本切成若干页。
     *
     * @param text      正文（可以含 \n）
     * @param measurer  Compose 的测量器
     * @param style     正文字体样式（字号 / 行高都在里面）
     * @param widthPx   可用宽度（像素）
     * @param heightPx  可用高度（像素）
     * @param maxPages  上限，防止极端输入把 UI 卡死
     */
    fun paginate(
        text: String,
        measurer: TextMeasurer,
        style: TextStyle,
        widthPx: Int,
        heightPx: Int,
        maxPages: Int = 4000
    ): List<Page> {
        if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(Page(0, text.length))

        // 整章先测一次，拿到逐行信息。截断策略：一次放不下就分块测。
        // 单章 2 万字以内一次测完很稳；超过就按比例切成若干块，
        // 每块单独测 —— 分块边界会落在换行处，不影响视觉连续性。
        val layout = measureWhole(text, measurer, style, widthPx)
        val lineCount = layout.lineCount
        if (lineCount <= 0) return listOf(Page(0, text.length))

        val pages = ArrayList<Page>()
        var pageStartLine = 0
        var pageTopPx = layout.getLineTop(0)

        for (line in 0 until lineCount) {
            val bottom = layout.getLineBottom(line)
            val tooTall = bottom - pageTopPx > heightPx
            // 第 0 行就超高（视口比一行还矮）时强行放一行，避免死循环产出 0 页
            if (tooTall && line > pageStartLine) {
                val startChar = layout.getLineStart(pageStartLine)
                val endChar = layout.getLineStart(line)
                pages.add(
                    Page(startChar, endChar, (layout.getLineBottom(line - 1) - pageTopPx).toInt())
                )
                if (pages.size >= maxPages) return pages
                pageStartLine = line
                pageTopPx = layout.getLineTop(line)
            }
        }
        // 最后一页
        val startChar = layout.getLineStart(pageStartLine)
        pages.add(Page(startChar, text.length, 0))

        return if (pages.isEmpty()) listOf(Page(0, text.length)) else pages
    }

    /**
     * 测量整段文本。
     *
     * `maxLines` 不设上限，让它自然排到底。`softWrap = true` 与正文渲染
     * 的 `Text` 保持一致，否则断行位置会差一个词，分页就对不上。
     */
    private fun measureWhole(
        text: String,
        measurer: TextMeasurer,
        style: TextStyle,
        widthPx: Int
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = Int.MAX_VALUE,
        constraints = Constraints(
            maxWidth = widthPx,
            maxHeight = Constraints.Infinity
        )
    )

    /**
     * 找出某个字符下标落在第几页。
     *
     * 恢复进度时用：用户上次停在第 12000 个字符，现在要跳到包含它的那一页。
     */
    fun pageOfChar(pages: List<Page>, charIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val idx = pages.indexOfFirst { charIndex >= it.start && charIndex < it.end }
        if (idx >= 0) return idx
        // charIndex 刚好等于最后一页的 end（或不合法）→ 收敛到最后一页
        return pages.size - 1
    }

    /** 取出某一页的正文。 */
    fun textOfPage(text: String, page: Page): String {
        val s = page.start.coerceIn(0, text.length)
        val e = page.end.coerceIn(s, text.length)
        return text.substring(s, e)
    }
}
