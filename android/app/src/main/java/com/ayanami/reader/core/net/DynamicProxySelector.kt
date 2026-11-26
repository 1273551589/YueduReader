package com.ayanami.reader.core.net

import android.content.Context
import com.ayanami.reader.data.Settings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 读取用户设置代理的 [ProxySelector]。
 *
 * ### 为什么不用 OkHttp 的 `.proxy(...)`
 *
 * `.proxy()` 是**建 Client 时固定**的。Coil 的 `ImageLoader` 在
 * App 启动时只建一次，那时用户可能还没填代理；用户填完再改设置，
 * 已经建好的 client 不会变 —— 非要重启 App 才生效。
 *
 * `ProxySelector` 每**次**请求都会被调用，所以在这里现读设置，
 * 改代理/清代理立刻生效，零重启。
 *
 * ### 解析规则（与 [Settings.proxyUrl] 的写法对齐）
 *
 * | 用户输入 | 解析结果 |
 * |---|---|
 * | 空 / 空白 | `Proxy.NO_PROXY`（直连） |
 * | `192.168.1.10:7890` | HTTP 代理（默认端口 7890） |
 * | `http://host:port` | HTTP 代理 |
 * | `socks5://host:port` | SOCKS 代理（默认端口 1080） |
 * | `socks://host:port` | SOCKS 代理 |
 *
 * 解析失败一律退回直连 —— 一个写错的代理串不该让整个 App 断网。
 */
class DynamicProxySelector(private val context: Context) : ProxySelector() {

    private val settings: Settings by lazy { Settings(context.applicationContext) }

    /** 每 500ms 才读一次 SharedPreferences，避免列表快速滚动时反复 IPC。 */
    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedRaw: String = ""

    @Volatile
    private var cachedProxy: Proxy = Proxy.NO_PROXY

    override fun select(uri: URI?): MutableList<Proxy> {
        val proxy = currentProxy()
        return mutableListOf(proxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // 代理连不上时 OkHttp 会回调这里。不改缓存 ——
        // 用户可能只是临时网络抖动，下一次请求仍然继续尝试。
    }

    private fun currentProxy(): Proxy {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cachedProxy

        val raw = runCatching { settings.proxyUrl }.getOrDefault("")
        val proxy = parse(raw)
        cachedRaw = raw
        cachedProxy = proxy
        cachedAt = now
        return proxy
    }

    companion object {
        private const val CACHE_MS = 500L

        /**
         * 把设置里的代理串解析成 [Proxy]。
         *
         * 纯函数，便于单测 —— 代理串的写法五花八门，这里是唯一的真相点。
         */
        fun parse(raw: String?): Proxy {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return Proxy.NO_PROXY

            val isSocks = s.startsWith("socks5://", true) || s.startsWith("socks://", true)
            val withScheme = when {
                s.contains("://") -> s
                else -> "http://$s"
            }
            val (host, port) = try {
                val uri = URI(withScheme)
                uri.host to uri.port
            } catch (e: Exception) {
                return Proxy.NO_PROXY
            }
            if (host.isNullOrBlank()) return Proxy.NO_PROXY

            val realPort = if (port > 0) port else if (isSocks) 1080 else 7890
            val type = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress.createUnresolved(host, realPort))
        }
    }
}
