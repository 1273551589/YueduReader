package com.ayanami.reader

import com.ayanami.reader.core.net.HostGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨源搜索并发策略的契约测试。
 *
 * ### 要锁住的三件事
 *
 * 1. **同域名必须串行** —— 并发打同一个站点是找封
 * 2. **跨域名必须真并行** —— 这是「搜索不再卡」的全部理由
 * 3. **总耗时按最慢一档算，不是累加** —— 20 个源串行的时代要结束
 *
 * v0.6.0 的写法是 `for (src in enabled) { ... }` 串行 + 全局 1000ms 间隔，
 * 20 个源要等 20 秒以上。这里用假延迟模拟真实网络，验证新策略的差异。
 */
class SearchConcurrencyTest {

    @After
    fun cleanup() {
        HostGate.reset()
        HostGate.reconfigure(4)
    }

    /** 模拟一次「某书源的搜索」：在闸门保护下 sleep 若干毫秒。 */
    private suspend fun fakeSearch(url: String, costMs: Long) {
        HostGate.run(url, intervalMs = 0) {
            Thread.sleep(costMs)
        }
    }

    @Test
    fun `serial baseline is slow`() = runBlocking {
        HostGate.reset()
        val t0 = System.currentTimeMillis()
        repeat(8) { fakeSearch("https://host$it.test/s", 60) }
        val serial = System.currentTimeMillis() - t0
        // 串行 8 × 60ms ≈ 480ms+，只要确认它确实是累加的
        assertTrue("串行应接近累加耗时，实测 ${serial}ms", serial >= 420)
    }

    @Test
    fun `parallel by host is much faster than serial`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(4)

        val t0 = System.currentTimeMillis()
        coroutineScope {
            // 按域名分组，每组一个协程 —— 这正是 ReaderViewModel.search 的做法
            (0 until 8).map { i ->
                async(Dispatchers.IO) { fakeSearch("https://host$i.test/s", 60) }
            }.awaitAll()
        }
        val parallel = System.currentTimeMillis() - t0

        // 8 个不同域名、4 并发 → 两轮 = 约 120ms，远小于 480ms
        assertTrue("跨域名 4 并发应显著快于串行，实测 ${parallel}ms", parallel < 400)
    }

    @Test
    fun `same host group is serialized inside one coroutine`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(4)
        val peak = AtomicInteger(0)
        val cur = AtomicInteger(0)

        coroutineScope {
            // 4 个源同属一个域名 → groupBy 之后落进同一个协程，必然串行
            async(Dispatchers.IO) {
                repeat(4) {
                    HostGate.run("https://same.test/s", intervalMs = 0) {
                        val n = cur.incrementAndGet()
                        if (n > peak.get()) peak.set(n)
                        Thread.sleep(30)
                        cur.decrementAndGet()
                    }
                }
            }.await()
        }
        assertEquals("同域名同组必须串行", 1, peak.get())
    }

    @Test
    fun `grouping by host puts same site sources together`() {
        // 这是 search() 里的分组逻辑，纯函数性质，直接验算
        val sources = listOf(
            "https://a.com/1" to "a1",
            "https://a.com/2" to "a2",   // 同站不同频道
            "https://b.com/1" to "b1",
            "https://c.com/1" to "c1"
        )
        val grouped = sources.groupBy { HostGate.hostOf(it.first) }
        assertEquals("应有 3 个域名", 3, grouped.size)
        assertEquals("a.com 下应有 2 个源", 2, grouped["a.com"]!!.size)
        assertEquals(1, grouped["b.com"]!!.size)
    }

    @Test
    fun `many sources do not exhaust coroutines`() = runBlocking {
        // 20 个源、4 并发 + 同域串行：不能因为排队死锁
        HostGate.reset()
        HostGate.reconfigure(4)
        val done = AtomicInteger(0)
        coroutineScope {
            (0 until 20).map { i ->
                async(Dispatchers.IO) {
                    fakeSearch("https://h${i % 7}.test/s", 15)
                    done.incrementAndGet()
                }
            }.awaitAll()
        }
        assertEquals("20 个源必须全部完成", 20, done.get())
    }

    @Test
    fun `global cap prevents unbounded parallelism`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(2)
        val cur = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coroutineScope {
            (0 until 10).map { i ->
                async(Dispatchers.IO) {
                    HostGate.run("https://cap$i.test/s", intervalMs = 0) {
                        val n = cur.incrementAndGet()
                        if (n > peak.get()) peak.set(n)
                        Thread.sleep(25)
                        cur.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertTrue("全局并发不得超过 2，实测峰值 ${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun `search interval is per host not global`() = runBlocking {
        HostGate.reset()
        HostGate.reconfigure(4)
        // 两个不同域名各带 200ms 间隔，应该并行等待而不是互相拖累
        val t0 = System.currentTimeMillis()
        coroutineScope {
            listOf("https://x.test", "https://y.test").map { url ->
                async(Dispatchers.IO) {
                    HostGate.run(url, intervalMs = 200) { Thread.sleep(50) }
                    HostGate.run(url, intervalMs = 200) { Thread.sleep(50) }
                }
            }.awaitAll()
        }
        val elapsed = System.currentTimeMillis() - t0
        // 各自 2 次 = 50+200+50 ≈ 300ms；如果间隔是全局的就要 600ms+
        assertTrue("间隔应按域名独立计算，实测 ${elapsed}ms", elapsed < 560)
    }

    @Test
    fun `host parsing handles ports and case`() {
        assertEquals("a.com", HostGate.hostOf("https://A.COM:8443/x"))
        assertEquals("b.cn", HostGate.hostOf("http://b.cn/x?y=1"))
    }
}
