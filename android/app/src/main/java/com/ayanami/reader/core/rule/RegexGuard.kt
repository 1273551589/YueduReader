package com.ayanami.reader.core.rule

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 正则超时保护（方案 §3.3.2）。
 *
 * 为什么必须自己做：Java 的 `Pattern` **不响应 `Thread.interrupt`**。
 * 一个灾难性回溯的 pattern，会把正则匹配线程永久卡住 ——
 * 它不会因为 interrupt 退出，也不会因为 HTTP 超时退出（已经在本地计算了）。
 *
 * ⚠️ **注意选测试用例**：教科书上那批 ReDoS 例子（`(a+)+$`、`(a|aa)+$`）
 * 在 **JDK 9+ 已经被度量化优化掉了**，实测只跑 0–1ms，拿来当测试会误通过。
 * 真正能卡住的是 `(.*a){30}` 这种 —— 实测 **14.5 秒**（JDK 17 / 100 字符输入）。
 *
 * 所以只能：
 * 1. 在**独立线程**里跑匹配
 * 2. 超时后**放弃这个线程**（`Thread.stop` 已移除，JVM 上确实杀不掉）
 * 3. 累计泄漏计数，超过阈值就熔断，不再跑正则
 *
 * ⚠️ **这条和 JS 超时不一样，别指望能真中断。**
 * `java.util.regex.Pattern` 的匹配循环**没有**任何可挂 hook 的计数器
 * （不像 Rhino 有 `observeInstructionCount`），所以在 JVM 上**没有**
 * "设个预算就能自动抛异常"的办法。这里只能丢线程。
 *
 * 后果：一个 `(.*a){30}` 这样的 pattern（实测 **14.5 秒**）会让
 * 一个 `regex-guard` 线程挂 14.5 秒。线程池是 `CachedThreadPool`，
 * 所以不会饿死其他任务，但泄漏计数会涨 —— 涨到 [maxAbandoned] 就熔断。
 *
 * 这也是为什么 §3.2.5 的每源间隔不能省：正则炸了还有个兜底。
 */
object RegexGuard {

    /** 单次正则匹配的上限。 */
    @Volatile
    var timeoutMs: Long = 800L

    /** 累计被放弃的线程数。 */
    val abandoned: AtomicInteger = AtomicInteger(0)

    /** 最近一次正则编译失败的原因；为 null 表示编译没问题。 */
    private val compileError = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun consumeCompileError(): String? = compileError.getAndSet(null)

    /** 熔断阈值：泄漏太多说明有毒源在反复触发，直接停掉正则。 */
    @Volatile
    var maxAbandoned: Int = 12

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "regex-guard").apply { isDaemon = true }
    }

    val isTripped: Boolean get() = abandoned.get() >= maxAbandoned

    fun reset() {
        abandoned.set(0)
    }

    /**
     * 带超时的替换。
     * @return 替换结果；超时返回 null（调用方决定拿原文还是判失败）
     */
    fun replaceTimeout(
        input: String,
        pattern: String,
        replacement: String,
        onlyFirst: Boolean
    ): String? {
        if (isTripped) return null

        val future: Future<String?> = pool.submit<String?> {
            // 正则编译失败必须**显式**上报，不能静默返回原文。
            // 否则用户分不清「我的正则写错了」和「正则没错但没匹配上」——
            // 实测：`##(unclosed` 和 `##zzz`（合法但不匹配）表现完全一样。
            val regex = try {
                Regex(pattern)
            } catch (e: Exception) {
                compileError.set(e.message ?: "正则语法错误")
                return@submit input
            }
            try {
                if (onlyFirst) {
                    val m = regex.find(input)
                    if (m == null) input
                    else {
                        val expanded = expand(replacement, m)
                        input.substring(0, m.range.first) + expanded +
                            input.substring(m.range.last + 1)
                    }
                } else {
                    regex.replace(input) { m -> expand(replacement, m) }
                }
            } catch (e: Exception) {
                input
            }
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            abandoned.incrementAndGet()
            null
        }
    }

    /** 支持 `$1` 组引用与 `\n` `\t` 转义。 */
    fun expand(replRaw: String, m: MatchResult): String {
        val sb = StringBuilder()
        var i = 0
        while (i < replRaw.length) {
            val c = replRaw[i]
            if (c == '\\' && i + 1 < replRaw.length) {
                when (replRaw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    else -> { sb.append(replRaw[i + 1]); i += 2; continue }
                }
            }
            if (c == '$' && i + 1 < replRaw.length && replRaw[i + 1].isDigit()) {
                var j = i + 1
                var num = 0
                while (j < replRaw.length && replRaw[j].isDigit()) {
                    num = num * 10 + (replRaw[j] - '0'); j++
                }
                sb.append(m.groupValues.getOrNull(num) ?: "")
                i = j
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
