package com.ayanami.reader

import com.ayanami.reader.core.pixiv.PixivSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原图地址还原的契约测试。
 *
 * ### 为什么这个转换必须精确
 *
 * 列表上显示的是 `c/480x960/` 的缩略图（省流量），但「保存原图」要的是
 * 原始文件。拼错就是 404 —— 用户点保存得到「保存失败」，
 * 而他根本无从判断是网络问题还是 App 的问题。
 */
class PixivSaverTest {

    private val thumb =
        "https://i.pximg.net/c/480x960/img-master/img/2026/11/24/01/24/03/152421696_p0_master1200.jpg"

    @Test
    fun `thumbnail converts to original path dropping transcoded suffix`() {
        val out = PixivSaver.toOriginalUrl(thumb)
        // ⚠️ 必须去掉 _master1200。实测：
        //   img-original/.../152421696_p0_master1200.jpg → 404
        //   img-original/.../152421696_p0.jpg            → 200（7.18 MB）
        assertEquals(
            "https://i.pximg.net/img-original/img/2026/11/24/01/24/03/152421696_p0.jpg",
            out
        )
        assertTrue("不能残留 _master1200", !out.contains("_master1200"))
    }

    @Test
    fun `already original is untouched`() {
        val orig = "https://i.pximg.net/img-original/img/2026/11/24/22/57/02/152446633_p0.jpg"
        assertEquals(orig, PixivSaver.toOriginalUrl(orig))
    }

    @Test
    fun `non pixiv url is returned as is`() {
        val other = "https://example.com/a/b.jpg"
        assertEquals(other, PixivSaver.toOriginalUrl(other))
    }

    @Test
    fun `empty url is safe`() {
        assertEquals("", PixivSaver.toOriginalUrl(""))
    }

    @Test
    fun `candidates include png and jpg`() {
        val c = PixivSaver.originalCandidates(thumb)
        assertTrue("应包含 png 候选（Pixiv 原图多为 png）", c.any { it.endsWith(".png") })
        assertTrue("应包含 jpg 候选", c.any { it.endsWith(".jpg") })
        assertTrue("候选必须落在 img-original 下", c.all { it.contains("/img-original/") })
    }

    @Test
    fun `candidates keep the numeric id`() {
        val c = PixivSaver.originalCandidates(thumb)
        assertTrue("文件名里的作品 ID 不能丢", c.all { it.contains("152421696_p0") })
    }

    @Test
    fun `unknown pattern falls back to original url`() {
        // 认不出的路径要老实返回原地址，不要瞎拼一个 404
        val odd = "https://i.pximg.net/some/weird/path.jpg"
        assertEquals(odd, PixivSaver.toOriginalUrl(odd))
    }

    @Test
    fun `user profile images are not converted`() {
        val avatar = "https://i.pximg.net/user-profile/img/2026/07/20/29112187_x_50.jpg"
        assertEquals(
            "作者头像不在 img-master 下，不该被改写",
            avatar,
            PixivSaver.toOriginalUrl(avatar)
        )
    }

    @Test
    fun `custom thumb is converted`() {
        val custom =
            "https://i.pximg.net/custom-thumb/img/2026/11/24/01/24/03/152421696_p0_custom1200.jpg"
        val out = PixivSaver.toOriginalUrl(custom)
        assertTrue(out.contains("/img-original/"))
        assertTrue(out.contains("152421696_p0"))
    }

    // ---------------- 图片内容校验 ----------------

    @Test
    fun `tiny json body is rejected as image`() {
        // Pixiv 的 404 体是 58 字节 JSON，绝不能当图片存进相册
        val fake = """{"error":"not found"}""".toByteArray()
        assertTrue("错误 JSON 必须被拒", !PixivSaver.isImage(fake))
    }

    @Test
    fun `png header is accepted`() {
        val png = ByteArray(2048).also {
            it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte()
            it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte()
        }
        assertTrue(PixivSaver.isImage(png))
        assertEquals("image/png", PixivSaver.mimeOf(png))
    }

    @Test
    fun `jpeg header is accepted`() {
        val jpeg = ByteArray(2048).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
        }
        assertTrue(PixivSaver.isImage(jpeg))
        assertEquals("image/jpeg", PixivSaver.mimeOf(jpeg))
    }

    @Test
    fun `empty body is rejected`() {
        assertTrue(!PixivSaver.isImage(ByteArray(0)))
    }

    @Test
    fun `undersized payload is rejected`() {
        // 小于 1KB 的不可能是真图
        val small = ByteArray(100).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        assertTrue("过小的载荷要拒掉", !PixivSaver.isImage(small))
    }
}
