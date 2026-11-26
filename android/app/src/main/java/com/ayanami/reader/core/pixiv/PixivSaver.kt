package com.ayanami.reader.core.pixiv

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 把图片存进系统相册。
 *
 * ### 为什么必须走 MediaStore
 *
 * Android 10+ 的分区存储里，App 往公共目录直接写文件会被拒。
 * `MediaStore.Images` 是唯一不需要存储权限就能写进相册的通道
 * （而且写进去之后相册应用立刻能识别）。
 *
 * ### 必须走自己的 client
 *
 * 图床要 `Referer`，而且**保存的是原图**（不是缩略图）——
 * 原图 URL 在 `pageUrls` 里，而列表上显示的是 `c/480x960/` 的缩略地址。
 * 所以保存前要把 URL 还原成 `img-original` 形式。
 */
object PixivSaver {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 把缩略图/压缩图地址还原成原图地址。
     *
     * ### Pixiv 图床的两种文件名（实测）
     *
     * | 目录 | 文件名 | 尺寸 |
     * |---|---|---|
     * | `c/480x960/img-master/` | `152421696_p0_master1200.jpg` | 449×960 |
     * | `img-original/` | `152421696_p0.jpg` | 原尺寸（示例 7.18 MB） |
     *
     * 关键点：**原图文件名里没有 `_master1200`** ——
     * `_master1200` 是 `img-master` 目录下的转码标记。
     *
     * 第一版保留了 `_master1200` 直接拼 `img-original`，
     * 结果 404 → fallback 到缩略图 → 「保存原图」实际存了 449×960
     * （实测确认：文件只有 682 KB，而真原图 7.18 MB）。
     *
     * 所以要做三件事：
     * ① 去掉尺寸段 `c/WxH/`
     * ② `img-master` → `img-original`
     * ③ 去掉文件名里的 `_master1200`
     */
    fun toOriginalUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.contains("/img-original/")) return url

        val m = Regex("""^(https?://[^/]+)/(?:c/[\dx]+/)?(?:img-master|custom-thumb)/(.+)$""")
            .find(url) ?: return url
        val host = m.groupValues[1]
        val rest = m.groupValues[2]
            // _master1200 是转码标记，原图没有它
            .replace(Regex("""_master1200(\.[a-zA-Z]+)$"""), "$1")
        return "$host/img-original/$rest"
    }

    /**
     * 原图扩展名候选。
     *
     * Pixiv 原图可能是 png（插画多为 png）或 jpg（摄影/部分投稿），
     * 无法从 URL 判断，只能两个都试。顺序按经验：pn​g 优先。
     */
    fun originalCandidates(url: String): List<String> {
        val base = toOriginalUrl(url)
        if (!base.contains("/img-original/")) return listOf(url)
        val noExt = base.substringBeforeLast('.', base)
        // 同一个候选列表里 png / jpg 都要有，且去掉重复
        return listOf("$noExt.png", "$noExt.jpg").distinct()
    }

    /**
     * 下载并写入相册。
     *
     * @param displayUrl 界面上正在显示的那个地址（会尝试还原成原图）
     * @return 成功时返回写入后的显示名
     */
    suspend fun save(context: Context, displayUrl: String, fileNameHint: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = downloadFirstWorking(displayUrl)
                    ?: throw IllegalStateException("所有候选地址都下载失败（可能需要代理）")
                writeToGallery(context, bytes, fileNameHint)
            }
        }

    /** 最小可信图片大小。Pixiv 的 404 响应体是 58 字节的 JSON 错误。 */
    private const val MIN_IMAGE_BYTES = 1024

    /**
     * 依次尝试原图候选地址，返回第一个**真的是图片**的字节。
     *
     * ⚠️ 两道校验缺一不可：
     * ① HTTP 2xx —— 404 会返回 58 字节的 `{"error":...}`
     * ② 大小 > 1KB 且文件头是 PNG/JPEG —— 防止把错误 JSON 存成 .jpg
     *    （实测踩过：状态码对了但内容不是图，相册里会出现打不开的破图）
     */
    private fun downloadFirstWorking(displayUrl: String): ByteArray? {
        for (candidate in originalCandidates(displayUrl) + displayUrl) {
            val req = Request.Builder()
                .url(candidate)
                .header("Referer", "https://www.pixiv.net/")
                .header("User-Agent", PixivClient.UA)
                .build()
            val bytes = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.code in 200..299) resp.body.bytes() else null
                }
            }.getOrNull()
            if (bytes != null && isImage(bytes)) return bytes
        }
        return null
    }

    /** 文件头判断：PNG / JPEG。 */
    internal fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_IMAGE_BYTES) return false
        val png = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val jpeg = bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val webp = bytes.size > 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()
        return png || jpeg || webp
    }

    /** 按文件头决定 MIME，别一律写 image/jpeg。 */
    internal fun mimeOf(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() -> "image/png"
        bytes.size > 12 && bytes[0] == 'R'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    private fun writeToGallery(context: Context, bytes: ByteArray, hint: String): String {
        val stamp = System.currentTimeMillis()
        // ⚠️ 文件名要清掉一切非法字符，包括 emoji —— Pixiv 标题里 emoji 很常见
        // （实测有一篇叫「🌙」），直接拼进文件名在某些文件系统上会失败。
        val safeHint = hint
            .replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_")
            .replace(Regex("""[^\p{L}\p{N}_\- ]"""), "")
            .trim()
            .take(40)
        val ext = if (mimeOf(bytes) == "image/png") "png" else "jpg"
        val name = buildString {
            append("pixiv_")
            if (safeHint.isNotBlank()) { append(safeHint); append('_') }
            append(stamp)
            append('.').append(ext)
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(bytes))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YueduReader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在相册创建文件")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入相册")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return name
    }
}
