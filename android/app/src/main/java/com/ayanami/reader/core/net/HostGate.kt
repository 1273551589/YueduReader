package com.ayanami.reader.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 按域名限流的并发闸门。
 *
 * ### 为什么不再用 `HttpFetcher.minIntervalPerHostMs`
 *
 * v0.6.0 的搜索是 `for (src in enabled)` 串行 + 一个**全局**的 1000ms 间隔。
 * 三个问题：
 *
 * 1. **串行 = 总耗时线性叠加**。20 个源、每源 1.5s，用户要等 30 秒；
 *    而这 20 个源分属 18 个不同域名，本来完全能并行。
 * 2. **全局间隔误伤不同域名**。1000ms 是「同一个 host 的礼貌间隔」，
 *    但那个变量是全局的：A 站刚请求完 B 站也得等 1 秒，跨域名并行被彻底抹平。
 * 3. **值会泄漏**。它是个 `@Volatile` 全局量，搜索设了 1000 忘了复位，
 *    后面的正文抓取、下载全被拖慢。
 *
 * ### 两种粒度，别混用
 *
 * - [run]：**完整闸门**。同 host 串行 + 间隔 + 跨 host 并发上限。
 *   用于「一个 host 代表一个源」的批量任务 —— 跨源搜索、全量体检。
 * - [pace]：**只做间隔**。不限制并发。
 *   用于同一个源内部的连续请求（搜索→详情→目录→正文、正文分页、批量下载），
 *   这些本来就是顺序 await 的，或者调用方自己已有并发策略（下载引擎按 host 限 2），
 *   再加一层 host 串行会白白把并行度压成 1。
 *
 * 两者共享同一张 `hostLastDone` 表，所以混用时仍然能正确错开。
 */
object HostGate {

    /** [run] 同时最多允许几个不同域名在跑。 */
    @Volatile
    var maxGlobal: Int = 4

    /**
     * 搜索聚合时同域名的间隔（毫秒）。
     *
     * 800ms 是折中：太快容易判爬虫，太慢 20 个源仍然要等很久。
     */
    @Volatile
    var searchIntervalMs: Long = 800L

    /** 体检时同域名的间隔，比搜索更保守。 */
    @Volatile
    var checkIntervalMs: Long = 3000L

    /** 阅读/下载时同域名的间隔。够礼貌，又不至于一章等一秒。 */
    @Volatile
    var readIntervalMs: Long = 200L

    /** 当前在跑的任务数（排查用）。 */
    val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private var globalSemaphore = Semaphore(maxGlobal)

    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

    /** 每个 host 上一次请求结束的时间戳。 */
    private val hostLastDone = ConcurrentHashMap<String, Long>()

    /** 累计等待记录，排查「为什么这么慢」用。 */
    val waitLog = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())

    fun reconfigure(max: Int) {
        if (max <= 0 || max == maxGlobal) return
        maxGlobal = max
        globalSemaphore = Semaphore(max)
    }

    /** 从 URL 取 host；解析不出来就退化成整串。 */
    fun hostOf(url: String): String {
        val h = runCatching { java.net.URI(url.trim()).host }.getOrNull()
        return h?.lowercase()?.takeIf { it.isNotBlank() } ?: url.trim()
    }

    private fun gateOf(host: String): Semaphore =
        hostSemaphores.getOrPut(host) { Semaphore(1) }

    private suspend fun awaitSlot(host: String, intervalMs: Long) {
        if (intervalMs <= 0) return
        val last = hostLastDone[host] ?: 0L
        val wait = last + intervalMs - System.currentTimeMillis()
        if (wait > 0) {
            if (waitLog.size < 200) waitLog.add(host to wait)
            delay(wait)
        }
    }

    /**
     * 完整闸门：跨 host 并发上限 + 同 host 串行 + 间隔。
     *
     * @param url        目标地址，用来取 host
     * @param intervalMs 同 host 最小间隔；0 表示不限
     */
    suspend fun <T> run(
        url: String,
        intervalMs: Long = searchIntervalMs,
        block: suspend () -> T
    ): T {
        val host = hostOf(url)
        return globalSemaphore.withPermit {
            gateOf(host).withPermit {
                // 进闸之后再等间隔 —— 反过来的话等待期间一直白占着全局许可
                awaitSlot(host, intervalMs)
                inFlight.incrementAndGet()
                try {
                    block()
                } finally {
                    inFlight.decrementAndGet()
                    hostLastDone[host] = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * 只做间隔，不管并发。
     *
     * 用于同一个源内部的连续请求 —— 那些本来就是顺序的，
     * 或者调用方自己管并发（下载引擎按 host 限 2）。
     */
    suspend fun <T> pace(
        url: String,
        intervalMs: Long = readIntervalMs,
        block: suspend () -> T
    ): T {
        val host = hostOf(url)
        awaitSlot(host, intervalMs)
        inFlight.incrementAndGet()
        return try {
            block()
        } finally {
            inFlight.decrementAndGet()
            hostLastDone[host] = System.currentTimeMillis()
        }
    }

    /** 测试用：清空所有状态。 */
    fun reset() {
        hostSemaphores.clear()
        hostLastDone.clear()
        waitLog.clear()
        inFlight.set(0)
    }
}
