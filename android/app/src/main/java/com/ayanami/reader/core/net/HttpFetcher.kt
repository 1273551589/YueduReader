package com.ayanami.reader.core.net

import com.ayanami.reader.core.rule.RuleError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.Charset
import javax.net.ssl.SSLException

/** 抓取结果：要么正文，要么结构化错误，不抛异常给上层。 */
sealed class FetchResult {
    data class Ok(val body: String, val finalUrl: String, val code: Int) : FetchResult()
    data class Err(val error: RuleError) : FetchResult()
}

/**
 * HTTP 层。
 *
 * 首版刻意用 [HttpURLConnection]（零依赖、能跑通），
 * 体检的礼貌策略（每 host 串行、403/429 不重试、5xx 最多一次退避）
 * 在这一层强制执行，见方案 §3.2.5。
 */
object HttpFetcher {

    /**
     * 用**桌面** UA：不少中文站（如 SF）见到移动 UA 会 302 到 m 站，
     * 而移动站的选择器与 PC 完全不同 —— 书源规则一般按 PC 页写。
     */
    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    var userAgent: String = DEFAULT_UA

    /** 同一 host 的最小请求间隔（毫秒）。体检用，交互阅读可调低。 */
    @Volatile
    var minIntervalPerHostMs: Long = 0L

    /** 请求计数，供体检报告和排查用。 */
    val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
    val requestLog = java.util.Collections.synchronizedList(ArrayList<String>())

    private val hostLastAccess = HashMap<String, Long>()
    private val hostLock = Any()

    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String>? = null,
        charsetName: String? = null,
        timeoutMs: Int = 15_000
    ): FetchResult = withContext(Dispatchers.IO) {
        val host = runCatching { URL(url).host }.getOrNull()
        if (host == null) {
            return@withContext FetchResult.Err(RuleError.NetworkRefused("URL 无法解析：$url"))
        }

        throttle(host)
        requestCount.incrementAndGet()
        if (requestLog.size < 100) requestLog.add("$method $url")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.uppercase()
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                headers?.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null && method.uppercase() != "GET") {
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        headers?.get("Content-Type") ?: "application/x-www-form-urlencoded"
                    )
                }
            }

            if (body != null && method.uppercase() != "GET") {
                conn.outputStream.use { it.write(body.toByteArray(charset(charsetName))) }
            }

            val code = conn.responseCode
            val finalUrl = conn.url.toString()

            // HttpURLConnection 对 301/302 不会自动跨协议跳，
            // 而且拿到的可能是重定向页本身，要手动跟随一次
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank() && location != url) {
                    val next = if (location.startsWith("http")) location
                    else com.ayanami.reader.core.rule.UrlHelper.resolve(url, location)
                    return@withContext fetch(next, method, body, headers, charsetName, timeoutMs)
                }
            }

            if (code >= 400) {
                // 403 / 429 / 503 是明确拒绝，上层不应重试
                return@withContext FetchResult.Err(RuleError.HttpStatus(code))
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            val cs = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: guessCharset(bytes, conn.contentType)
            FetchResult.Ok(String(bytes, cs), finalUrl, code)
        } catch (e: SocketTimeoutException) {
            FetchResult.Err(RuleError.NetworkTimeout("请求超时"))
        } catch (e: UnknownHostException) {
            FetchResult.Err(RuleError.NetworkRefused("域名解析失败"))
        } catch (e: SSLException) {
            FetchResult.Err(RuleError.NetworkRefused("TLS 错误：${e.message}"))
        } catch (e: IOException) {
            FetchResult.Err(RuleError.NetworkRefused(e.message ?: "IO 错误"))
        } catch (e: Exception) {
            FetchResult.Err(RuleError.NetworkRefused(e.message ?: e.javaClass.simpleName))
        } finally {
            conn?.disconnect()
        }
    }

    private fun charset(name: String?): Charset =
        name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    /** 从 `Content-Type` 或 HTML meta 猜编码；中文站 GBK 很常见。 */
    private fun guessCharset(bytes: ByteArray, contentType: String?): Charset {
        contentType?.let { ct ->
            Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.let {
                runCatching { Charset.forName(it.trim()) }.getOrNull()?.let { return it }
            }
        }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let {
            runCatching { Charset.forName(it.trim()) }.getOrNull()?.let { return it }
        }
        // 还要试 GBK：UTF-8 解码失败时回退
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return Charsets.UTF_8
        return runCatching { Charset.forName("GBK") }.getOrNull() ?: Charsets.UTF_8
    }

    private fun throttle(host: String) {
        val interval = minIntervalPerHostMs
        if (interval <= 0) return
        synchronized(hostLock) {
            val last = hostLastAccess[host] ?: 0L
            val wait = last + interval - System.currentTimeMillis()
            if (wait > 0) {
                runCatching { Thread.sleep(wait) }
            }
            hostLastAccess[host] = System.currentTimeMillis()
        }
    }
}
