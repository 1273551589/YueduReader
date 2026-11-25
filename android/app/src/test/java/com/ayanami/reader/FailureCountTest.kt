package com.ayanami.reader

import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.data.nextFailureCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住「连续失败计数」的合并逻辑。
 *
 * 真实 bug（v0.3.3 修复）：`saveCheckResult` 里写的是
 * ```
 * File(dir, "failures.json").writeText(gson.toJson(mapOf(result.sourceUrl to failures)))
 * ```
 * **只写当前这一个源**，多源时会把别的源整表覆盖掉。
 * 结果：「连续 3 次失败 → 建议删除」这个功能只有最后一条数据有效。
 */
class FailureCountTest {

    private fun result(url: String, status: SourceChecker.UiStatus) =
        SourceChecker.CheckResult(
            sourceUrl = url,
            status = status,
            elapsedMs = 100,
            stuckAt = "done"
        )

    private val READABLE = SourceChecker.UiStatus.READABLE
    private val FAILED = SourceChecker.UiStatus.CONNECT_FAILED

    @Test
    fun `失败时该源计数加一`() {
        val out = nextFailureCounts(emptyMap(), result("a", FAILED))
        assertEquals(1, out["a"])
    }

    @Test
    fun `连续失败时计数累加`() {
        var m: Map<String, Int> = emptyMap()
        repeat(3) { m = nextFailureCounts(m, result("a", FAILED)) }
        assertEquals(3, m["a"])
    }

    @Test
    fun `可读後计数归零`() {
        var m: Map<String, Int> = nextFailureCounts(emptyMap(), result("a", FAILED))
        m = nextFailureCounts(m, result("a", FAILED))
        assertEquals(2, m["a"])
        m = nextFailureCounts(m, result("a", READABLE))
        assertEquals(0, m["a"])
    }

    /**
     * 核心回归断言：**别的源的计数不能被覆盖**。
     *
     * 这正是 v0.3.3 之前那个 bug —— 注入回归（把实现改回
     * `mapOf(result.sourceUrl to n)`）时，这条会失败。
     */
    @Test
    fun `其他源的计数必须保留`() {
        var m = nextFailureCounts(emptyMap(), result("a", FAILED))
        m = nextFailureCounts(m, result("b", FAILED))
        m = nextFailureCounts(m, result("c", FAILED))

        assertEquals("a 丢了", 1, m["a"])
        assertEquals("b 丢了", 1, m["b"])
        assertEquals("c 丢了", 1, m["c"])
        assertEquals(3, m.size)
    }

    @Test
    fun `多源各自独立累加互不干扰`() {
        var m: Map<String, Int> = emptyMap()
        repeat(3) { m = nextFailureCounts(m, result("a", FAILED)) }
        m = nextFailureCounts(m, result("b", FAILED))
        m = nextFailureCounts(m, result("b", FAILED))

        assertEquals(3, m["a"])
        assertEquals(2, m["b"])
    }

    /** 方案 §4.2 的判据：连续 3 次失败 → 建议删除。 */
    @Test
    fun `连续三次失败可以判定为建议删除`() {
        var m: Map<String, Int> = emptyMap()
        repeat(3) { m = nextFailureCounts(m, result("bad", FAILED)) }
        assertEquals(true, (m["bad"] ?: 0) >= 3)
    }
}
