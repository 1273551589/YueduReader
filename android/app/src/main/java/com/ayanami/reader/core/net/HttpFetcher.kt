package com.ayanami.reader.core.net

import com.ayanami.reader.core.rule.RuleError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 抓取结果：要么正文，要么结构化错误，不抛异常给上层。 */
sealed class FetchResult {
    data class Ok(val body: String, val finalUrl: String, val code: Int) : FetchResult()
    data class Err(val error: RuleError) : FetchResult()
}

/**
 * HTTP 层。
 *
 * v0.4.0 起换成 **OkHttp**（以前是 `HttpURLConnection`）。换的理由不是"更潮"：
 *
 * - `HttpURLConnection` 对 301/302 **不跨协议跳**（http→https 会卡住），
 *   书源里这种跳转极常见，以前要手写一次手动跟随，还不完整
 * - 它没有连接池复用，26 条搜索结果 × 9 个字段的并发抓取会反复握手
 * - 图片站要注入 `Referer`（Pixiv 必需），拦截器是唯一干净的做法
 *
 * 体检的礼貌策略（每 host 串行、403/429 不重试）仍在**这一层**强制执行，
 * 见方案 §3.2.5。
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

    /**
     * 是否启用本层的同步限流。
     *
     * ⚠️ **默认 false**。v0.6.0 是默认开着的，那是个设计错误：
     *
     * 这一层用的是 `Thread.sleep`（在 OkHttp 的 IO 线程上），
     * 一旦有间隔，20 个源的并发搜索会**各自占住一个线程干等**，
     * Dispatchers.IO 的线程池很快被占满，后面的请求排队到超时 ——
     * 体感就是「搜索卡死」。
     *
     * 正确的做法是上层用协程 `delay` 排队（见 [HostGate]），
     * 不占线程。这里只在少数明确需要「粗暴阻塞」的场合才打开。
     */
    @Volatile
    var blockingThrottleEnabled: Boolean = false

    /** 附加请求头工厂（Pixiv 的 Referer 走这里，避免污染所有书源）。 */
    @Volatile
    var headerDecorator: ((String) -> Map<String, String>)? = null

    /** 请求计数，供体检报告和排查用。 */
    val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
    val requestLog = java.util.Collections.synchronizedList(ArrayList<String>())

    private val hostLastAccess = HashMap<String, Long>()
    private val hostLock = Any()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)   // HttpURLConnection 缺的就是这个
            .retryOnConnectionFailure(true)
            // 下载一本 400 章的书会频繁访问同一域名，连接池要给够。
            // 默认 5 个空闲连接，下载时同域名并发 2 但连续几百次请求，
            // 频繁建/拆连接会明显拖慢（实测每章多花 200~400ms）。
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String>? = null,
        charsetName: String? = null,
        timeoutMs: Int = 15_000
    ): FetchResult = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return@withContext FetchResult.Err(
                RuleError.NetworkRefused("地址不是绝对 URL：$target")
            )
        }
        val host = runCatching { java.net.URI(target).host }.getOrNull()
        if (host.isNullOrBlank()) {
            return@withContext FetchResult.Err(RuleError.NetworkRefused("URL 无法解析：$target"))
        }

        throttle(host)
        requestCount.incrementAndGet()
        if (requestLog.size < 100) requestLog.add("$method $target")

        val upperMethod = method.uppercase()
        try {
            val builder = Request.Builder()
                .url(target)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
            // ⚠️ 千万不要手动设 `Accept-Encoding`！
            // OkHttp 只在**它自己**设置该头时才做透明 gzip 解压；
            // 一旦我们插手，它就不再解压，resp.body.bytes() 拿到的是一堆
            // 压缩字节，转 String 后全是乱码 → 目录/正文全部 SelectorEmpty。
            // （实测：换 OkHttp 后 SF 书源 421 章目录直接变 0 章。）

            headers?.forEach { (k, v) -> builder.header(k, v) }
            headerDecorator?.invoke(target)?.forEach { (k, v) -> builder.header(k, v) }

            if (body != null && upperMethod != "GET" && upperMethod != "HEAD") {
                val cs = charset(charsetName)
                val contentType = headers?.get("Content-Type")
                    ?: "application/x-www-form-urlencoded; charset=${cs.name()}"
                builder.method(
                    upperMethod,
                    body.toByteArray(cs).toRequestBody(contentType.toMediaTypeOrNull())
                )
            } else {
                builder.method(upperMethod, null)
            }

            // ⚠️ 用共享 client + per-call Timeout，**不要** client.newBuilder().build()。
            // newBuilder().build() 会克隆出一个**新的连接池和线程池**，
            // 每次请求都建一份：下载 50 章 = 100 次请求 = 100 个连接池 +
            // 100 组 Dispatcher 线程，很快就耗尽 fd，请求卡死不动（实测卡在第 4 章）。
            val call = client.newCall(builder.build())

            call.execute().use { resp ->
                val code = resp.code
                if (code >= 400) {
                    // 403 / 429 / 503 是明确拒绝，上层不应重试
                    return@withContext FetchResult.Err(RuleError.HttpStatus(code))
                }
                val bytes = resp.body.bytes()
                val cs = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
                    ?: guessCharset(bytes, resp.header("Content-Type"))
                FetchResult.Ok(String(bytes, cs), resp.request.url.toString(), code)
            }
        } catch (e: java.io.InterruptedIOException) {
            // 包含 SocketTimeout 与 Timeout 两种
            FetchResult.Err(RuleError.NetworkTimeout("请求超时"))
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
        if (!blockingThrottleEnabled) return
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
