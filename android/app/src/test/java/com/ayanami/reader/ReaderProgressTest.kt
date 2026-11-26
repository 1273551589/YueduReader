package com.ayanami.reader

import com.ayanami.reader.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器设置与跨源对齐的契约测试。
 *
 * 两件事必须锁住：
 * 1. **行高换算** —— Compose 的 `lineHeight` 是绝对值（sp），
 *    设置里存的是倍数，乘错会让行距看起来很怪
 * 2. **跨源章节对齐** —— 换源后必须尽量停在原来读到的地方，
 *    跳回第 0 章是最烦人的体验
 */
class ReaderProgressTest {

    /** 与 `ReadScreen` 里的换算保持一致。 */
    private fun lineHeightSp(fontSize: Int, percent: Int): Float =
        fontSize * percent / 100f

    @Test
    fun `line height derived from font size and percent`() {
        assertEquals("18sp × 1.7 倍 = 30.6sp", 30.6f, lineHeightSp(18, 170), 0.01f)
        assertEquals("13sp × 1.2 倍 = 15.6sp", 15.6f, lineHeightSp(13, 120), 0.01f)
        assertEquals("30sp × 2.4 倍 = 72sp", 72f, lineHeightSp(30, 240), 0.01f)
    }

    @Test
    fun `line height is always bigger than font size`() {
        // 最低倍数 120% —— 行距小于字号会重叠，是硬错误
        for (size in Settings.MIN_FONT..Settings.MAX_FONT) {
            for (pct in 120..240 step 10) {
                val lh = lineHeightSp(size, pct)
                assertTrue("字号=$size 倍数=$pct 时行高 $lh 应大于字号", lh > size)
            }
        }
    }

    @Test
    fun `font size clamping range`() {
        assertEquals(13, Settings.MIN_FONT)
        assertEquals(30, Settings.MAX_FONT)
        assertTrue("默认字号应在范围内", 18 in Settings.MIN_FONT..Settings.MAX_FONT)
    }

    // ---------------- 跨源章节对齐 ----------------

    /** 与 `ReaderViewModelDownload.estimateIndex` 保持一致。 */
    private fun estimateIndex(oldIndex: Int, oldTotal: Int, newTotal: Int): Int {
        if (oldTotal <= 0 || newTotal <= 0) return 0
        val ratio = oldIndex.toDouble() / oldTotal
        return (ratio * newTotal).toInt().coerceIn(0, newTotal - 1)
    }

    @Test
    fun `same length sources keep exact index`() {
        assertEquals(50, estimateIndex(50, 100, 100))
    }

    @Test
    fun `shorter target source maps proportionally`() {
        // 老源 400 章读到第 200 章（50%），新源 200 章 → 约第 100 章
        assertEquals(100, estimateIndex(200, 400, 200))
    }

    @Test
    fun `longer target source maps proportionally`() {
        assertEquals(200, estimateIndex(100, 200, 400))
    }

    @Test
    fun `index never out of bounds`() {
        // 极端情况：老源 1000 章读到最后一章，新源只有 3 章
        val idx = estimateIndex(999, 1000, 3)
        assertTrue("不能越界，实际=$idx", idx in 0..2)
        // 空目录防护
        assertEquals(0, estimateIndex(5, 0, 10))
        assertEquals(0, estimateIndex(5, 10, 0))
    }

    @Test
    fun `rebuilding progress first chapter stays first`() {
        assertEquals(0, estimateIndex(0, 100, 50))
    }
}
