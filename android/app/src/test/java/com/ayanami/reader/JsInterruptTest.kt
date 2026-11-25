package com.ayanami.reader

import com.ayanami.reader.core.rule.InterruptibleContextFactory
import com.ayanami.reader.core.rule.JsBridge
import com.ayanami.reader.core.rule.JsRuntime
import com.ayanami.reader.core.rule.RuleError
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证「死循环能被真正打断」—— 这条不成立，`js-rule` 线程池会被永久耗尽。
 *
 * 历史：
 * - v0.2.0 只调 `setInstructionObserverThreshold(10_000)` 没重写回调 → 闹钟不响
 * - v0.3.0 重写了回调，但预算存在工厂全局变量上 → `JsRuntime(timeoutMs=600)`
 *   实际按全局 30s 跑；且 `cancelCurrent()` 从调用方线程调，是死代码
 * - v0.3.1 预算改 ThreadLocal，context 引用可外部持有
 */
class JsInterruptTest {

    private class EmptyBridge : JsBridge {
        override fun ajax(url: String, headers: Map<String, String>?): String? = null
        override fun log(msg: String) {}
        override fun base64Encode(data: String, flags: Int): String = data
        override fun base64Decode(data: String, flags: Int): String = data
        override fun encodeUri(s: String, enc: String): String = s
        override fun currentContent(): Any? = null
        override fun getElements(rule: String): List<Element> = emptyList()
        override fun putVar(key: String, value: String) {}
        override fun getVar(key: String): String? = null
    }

    @Test(timeout = 30_000)
    fun `死循环必须在预算内被打断`() {
        val rt = JsRuntime(EmptyBridge(), timeoutMs = 1_000)
        val t0 = System.currentTimeMillis()
        val out = rt.eval("while(true){}")
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(null, out)
        assertNotNull("必须报错，不能静默返回", rt.lastError)
        assertTrue("应判 JsTimeout，实际=${rt.lastError}", rt.lastError is RuleError.JsTimeout)
        assertTrue(
            "耗时 ${elapsed}ms 应接近 1000ms 预算（内层打断），不是被外层兜底",
            elapsed in 700..5_000
        )
        println("死循环在 ${elapsed}ms 被打断")
    }

    /**
     * 实例级预算：`JsRuntime(timeoutMs = 800)` 必须按 800ms 结束，不被别的实例影响。
     *
     * ⚠️ 这条**只能证明"没有明显串台"**，不能证明"内层真打断了"。
     * 原因：`future.get(800ms)` 无论内层对不对都会在 800ms 返回 ——
     * 想区分「内层打断」和「外层返回、内层还在烧 CPU」，得看
     * [JsInterruptTest.死循环线程最终真的终止]（线程必须是 TERMINATED / 无 RUNNABLE 残留）
     * 和 [BudgetIsolationTest]（直接断言预算字段）。
     *
     * 之前这条写的是 `elapsed < 5000`，太松：预算错用全局 30s 时照样绿。
     * 现在收紧到 `500..1500`，但**收紧本身不能替代因果验证** —— 见上面的分工。
     */
    @Test(timeout = 30_000)
    fun `实例预算不被其他实例污染`() {
        // 先在同池线程上跑一个"大预算"的实例
        val slow = JsRuntime(EmptyBridge(), timeoutMs = 20_000)
        assertEquals("ok", slow.eval("'ok'"))

        val fast = JsRuntime(EmptyBridge(), timeoutMs = 800)
        val t0 = System.currentTimeMillis()
        val out = fast.eval("var i=0; while(i<2000000000){i++;} i")
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(null, out)
        assertTrue(
            "800ms 预算应紧贴预算结束，实际 ${elapsed}ms",
            elapsed in 500..1_500
        )
        println("800ms 预算实例实际 ${elapsed}ms 结束")
    }

    @Test(timeout = 30_000)
    fun `enterWithBudget 返回的实例预算必须是本次的`() {
        val t = Thread {
            // 第一次：预算 20000
            val first = InterruptibleContextFactory.enterWithBudget(20_000)
            val firstBudget = first.debugBudget
            InterruptibleContextFactory.exitClean()

            // 第二次（同一个线程，Rhino 会复用 Context 实例）：预算 800
            val second = InterruptibleContextFactory.enterWithBudget(800)
            val secondBudget = second.debugBudget
            val sameInstance = (first === second)
            InterruptibleContextFactory.exitClean()

            assertEquals("第一次预算错", 20_000L, firstBudget)
            assertEquals(
                "同一线程第二次 enter 返回了复用实例（same=$sameInstance），预算未刷新：$secondBudget",
                800L, secondBudget
            )
        }
        t.start()
        t.join(20_000)
    }

    /** 两个实例的预算互不影响（并发也要各自生效）。 */
    @Test(timeout = 40_000)
    fun `并发时预算互不干扰`() {
        val fast = JsRuntime(EmptyBridge(), timeoutMs = 700)
        val slow = JsRuntime(EmptyBridge(), timeoutMs = 6_000)

        val fastResult = java.util.concurrent.atomic.AtomicLong(0)
        val t = Thread {
            val s = System.currentTimeMillis()
            fast.eval("var i=0; while(i<2000000000){i++;} i")
            fastResult.set(System.currentTimeMillis() - s)
        }
        t.start()
        // 同时跑一个正常短脚本，不该被 fast 的影响
        Thread.sleep(100)
        assertEquals("3.0", slow.eval("1+2"))
        t.join(20_000)

        assertTrue("fast 实例应早于 6s 结束，实际 ${fastResult.get()}ms", fastResult.get() < 6_000)
    }

    @Test(timeout = 20_000)
    fun `正常脚本不受影响`() {
        val rt = JsRuntime(EmptyBridge(), timeoutMs = 5_000)
        assertEquals("3.0", rt.eval("1+2"))   // Rhino 数字是 Double
        assertEquals("hi", rt.eval("'hi'"))
        assertEquals("3", rt.eval("String('abc'.length)"))
    }

    @Test(timeout = 20_000)
    fun `打断后 JsRuntime 仍可继续用`() {
        val rt = JsRuntime(EmptyBridge(), timeoutMs = 800)
        rt.eval("while(true){}")            // 先炸一次
        assertEquals("ok", rt.eval("'ok'"))  // 线程池没被耗尽，同一实例还能干活
    }

    /**
     * 线程要真的结束（`TERMINATED`），不能只是 Future 标记成已取消。
     * 这是 v0.2.0 那个 bug 的直接反例：当时 `interrupt()` 后线程仍是 RUNNABLE。
     */
    @Test(timeout = 30_000)
    fun `死循环线程最终真的终止`() {
        val seen = java.util.concurrent.ConcurrentHashMap<String, Thread>()
        val rt = JsRuntime(EmptyBridge(), timeoutMs = 800)
        rt.eval("while(true){}")

        // 给被打断的线程一点收尾时间
        Thread.sleep(1500)

        val alive = Thread.getAllStackTraces().keys.filter {
            it.name.startsWith("js-rule") && it.isAlive && it.state != Thread.State.WAITING
        }
        // 池里 2 个线程会一直活着等任务（WAITING）—— 那是正常的；
        // 关键是不能有 RUNNABLE 的死循环残留
        val spinning = alive.filter { it.state == Thread.State.RUNNABLE }
        assertTrue(
            "不该有 RUNNABLE 的残留脚本线程，实际=${spinning.map { "${it.name}/${it.state}" }}",
            spinning.isEmpty()
        )
    }
}
