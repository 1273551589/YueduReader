package com.ayanami.reader.core.pipeline

import com.ayanami.reader.core.net.FetchResult
import com.ayanami.reader.core.net.HttpFetcher
import com.ayanami.reader.core.rule.AndroidBase64Compat
import com.ayanami.reader.core.rule.AnalyzeRule
import com.ayanami.reader.core.rule.JsBridge
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * 把真实的抓取 / 解析能力接到 JS 宿主上。
 *
 * 注意：`@js` 里的 `java.ajax` 是**同步阻塞**调用，而 Rhino 跑在独立线程池里，
 * 所以这里用 `runBlocking` 是安全的（那个线程不是主线程，也不在协程调度器上）。
 */
class HttpJsBridge(
    private val session: SourceSession,
    private val currentContent: () -> Any?,
    private val analyzerProvider: () -> AnalyzeRule?
) : JsBridge {

    var logSink: ((String) -> Unit)? = null

    override fun ajax(url: String, headers: Map<String, String>?): String? {
        val res = runBlocking { HttpFetcher.fetch(url, headers = headers) }
        return when (res) {
            is FetchResult.Ok -> res.body
            is FetchResult.Err -> {
                logSink?.invoke("ajax 失败 ${res.error.humanMessage}: $url")
                null
            }
        }
    }

    override fun log(msg: String) {
        logSink?.invoke(msg)
    }

    override fun base64Encode(data: String, flags: Int): String =
        AndroidBase64Compat.encodeString(data, flags)

    override fun base64Decode(data: String, flags: Int): String =
        AndroidBase64Compat.decodeToString(data, flags)

    override fun encodeUri(s: String, enc: String): String =
        runCatching { URLEncoder.encode(s, enc) }.getOrDefault(s)

    override fun currentContent(): Any? = currentContent()

    override fun getElements(rule: String): List<Element> {
        val a = analyzerProvider() ?: return emptyList()
        return a.getElements(rule)
    }

    override fun putVar(key: String, value: String) {
        session.putSharedVar(key, value)
    }

    override fun getVar(key: String): String? = session.getSharedVar(key)
}
