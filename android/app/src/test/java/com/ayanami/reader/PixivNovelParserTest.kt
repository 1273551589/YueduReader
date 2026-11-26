package com.ayanami.reader

import com.ayanami.reader.core.pixiv.PixivNovelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixiv 小说正文语法解析的契约测试。
 *
 * 四类标记各有自己的坑：
 * - `[newpage]` 是**分页/换场**，不能直接删（会把两段粘成一段）
 * - `[chapter:标题]` 决定目录，切错就整章错位
 * - `[[rb:汉字>假名]]` 注音，只留汉字或只留假名都会丢信息
 * - `[jump:N]` 必须清干净，留在正文里就是噪音
 */
class PixivNovelParserTest {

    // ---------------- [newpage] ----------------

    @Test
    fun `newpage becomes paragraph break not deletion`() {
        val raw = "第一段。\n[newpage]\n第二段。"
        val p = PixivNovelParser.parse(raw)
        // 不能粘连成「第一段。第二段。」
        assertTrue("newpage 应产生段落分隔", p.plainText.contains("第一段。\n\n第二段。"))
        assertFalse("不能留标记本身", p.plainText.contains("[newpage]"))
        assertTrue("应记录含分页标记", p.hasNewPage)
    }

    @Test
    fun `newpage is case insensitive`() {
        val p = PixivNovelParser.parse("A[NewPage]B")
        assertTrue(p.hasNewPage)
        assertFalse(p.plainText.contains("NewPage", ignoreCase = true))
    }

    @Test
    fun `text without newpage is not flagged`() {
        assertFalse(PixivNovelParser.parse("只有一段").hasNewPage)
    }

    // ---------------- [chapter:] ----------------

    @Test
    fun `chapters are split by chapter marker`() {
        val raw = "[chapter:第一章 开始]\n正文一\n[chapter:第二章 继续]\n正文二"
        val p = PixivNovelParser.parse(raw)
        assertTrue("应识别为有章节", p.hasChapters)
        assertEquals(2, p.sections.size)
        assertEquals("第一章 开始", p.sections[0].title)
        assertEquals("正文一", p.sections[0].body.trim())
        assertEquals("第二章 继续", p.sections[1].title)
        assertEquals("正文二", p.sections[1].body.trim())
    }

    @Test
    fun `preamble before first chapter is kept`() {
        val raw = "这是前言。\n[chapter:第一章]\n正文"
        val p = PixivNovelParser.parse(raw)
        assertEquals("前言应作为无标题章节保留", 2, p.sections.size)
        assertNull(p.sections[0].title)
        assertEquals("这是前言。", p.sections[0].body.trim())
        assertEquals("第一章", p.sections[1].title)
    }

    @Test
    fun `no chapter marker yields single untitled section`() {
        val p = PixivNovelParser.parse("就是一整篇没有章节的短文。")
        assertEquals(1, p.sections.size)
        assertNull(p.sections[0].title)
        assertFalse(p.hasChapters)
    }

    @Test
    fun `empty chapter title is allowed`() {
        val p = PixivNovelParser.parse("[chapter:]\n正文")
        assertEquals(1, p.sections.size)
        assertEquals("", p.sections[0].title)
    }

    // ---------------- [[rb:汉字>假名]] ----------------

    @Test
    fun `ruby renders as base with reading`() {
        val p = PixivNovelParser.parse("彼女は[[rb:薔薇>ばら]]が好き。")
        assertEquals("应渲染成「汉字（假名）」", "彼女は薔薇（ばら）が好き。", p.plainText)
        assertEquals(1, p.rubyCount)
    }

    @Test
    fun `ruby without reading keeps base only`() {
        val p = PixivNovelParser.parse("[[rb:薔薇]]")
        assertEquals("薔薇", p.plainText)
    }

    @Test
    fun `multiple rubies all render`() {
        val raw = "[[rb:今日>きょう]]は[[rb:明日>あした]]より[[rb:忙>いそが]]しい"
        val p = PixivNovelParser.parse(raw)
        assertEquals("今日（きょう）は明日（あした）より忙（いそが）しい", p.plainText)
        assertEquals(3, p.rubyCount)
    }

    @Test
    fun `ruby count survives chapter split`() {
        val raw = "[chapter:A]\n[[rb:猫>ねこ]]\n[chapter:B]\n[[rb:犬>いぬ]]"
        val p = PixivNovelParser.parse(raw)
        assertEquals(2, p.rubyCount)
        assertEquals("猫（ねこ）", p.sections[0].body.trim())
        assertEquals("犬（いぬ）", p.sections[1].body.trim())
    }

    // ---------------- [jump:] 与其它标记 ----------------

    @Test
    fun `jump markers are removed completely`() {
        val p = PixivNovelParser.parse("第一段\n[jump:2]\n第二段")
        assertFalse("jump 标记不能残留", p.plainText.contains("[jump"))
        assertTrue(p.plainText.contains("第一段"))
        assertTrue(p.plainText.contains("第二段"))
    }

    @Test
    fun `jumpuri keeps link text drops url`() {
        val p = PixivNovelParser.parse("详见[[jumpuri:这里>https://example.com]]。")
        assertEquals("详见这里。", p.plainText)
    }

    @Test
    fun `pixivimage becomes readable placeholder`() {
        val p = PixivNovelParser.parse("看图[pixivimage:123456]结束")
        assertTrue("应变成可读占位而不是留一串 ID", p.plainText.contains("［插图 123456］"))
    }

    @Test
    fun `pixivimage with page suffix`() {
        val p = PixivNovelParser.parse("[pixivimage:123456-2]")
        assertTrue(p.plainText.contains("［插图 123456］"))
    }

    // ---------------- 边界 ----------------

    @Test
    fun `empty input is safe`() {
        val p = PixivNovelParser.parse("")
        assertEquals(1, p.sections.size)
        assertEquals("", p.plainText)
    }

    @Test
    fun `blank input is safe`() {
        val p = PixivNovelParser.parse("   \n\n  ")
        assertEquals("", p.plainText)
    }

    @Test
    fun `crlf is normalized`() {
        val p = PixivNovelParser.parse("行一\r\n行二\r行三")
        assertFalse("不能留 \r", p.plainText.contains('\r'))
        assertTrue(p.plainText.contains("行一"))
        assertTrue(p.plainText.contains("行三"))
    }

    @Test
    fun `excessive blank lines collapse`() {
        val p = PixivNovelParser.parse("A\n\n\n\n\nB")
        assertFalse("连续空行应折叠", p.plainText.contains("\n\n\n"))
        assertTrue(p.plainText.contains("A"))
        assertTrue(p.plainText.contains("B"))
    }

    @Test
    fun `real pixiv novel sample round trips`() {
        // 真实 Pixiv 小说的典型结构
        val raw = """
            この作品はフィクションです。
            [newpage]
            [chapter:第一話　出会い]
            彼は[[rb:微笑>ほほえ]]んだ。
            「[[rb:久しぶり>ひさしぶり]]だね」
            [jump:2]
            [chapter:第二話　再会]
            また会えた。
        """.trimIndent()

        val p = PixivNovelParser.parse(raw)
        assertEquals("应拆出两章（前言被过滤或保留为无标题）", 2, p.sections.count { it.title != null })
        assertEquals("第一話　出会い", p.sections.first { it.title != null }.title)
        assertEquals(2, p.rubyCount)
        assertFalse(p.plainText.contains("[newpage]"))
        assertFalse(p.plainText.contains("[jump"))
        assertFalse(p.plainText.contains("[[rb:"))
        assertTrue(p.plainText.contains("微笑（ほほえ）"))
    }

    @Test
    fun `chapter body does not leak next chapter content`() {
        val raw = "[chapter:A]\nA的内容\n[chapter:B]\nB的内容"
        val p = PixivNovelParser.parse(raw)
        assertFalse("A 章不能含 B 的内容", p.sections[0].body.contains("B的内容"))
        assertFalse("B 章不能含 A 的内容", p.sections[1].body.contains("A的内容"))
    }
}
