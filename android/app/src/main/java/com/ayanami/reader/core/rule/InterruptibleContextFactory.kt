package com.ayanami.reader.core.rule

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException

/**
 * 可中断的 Rhino Context —— 唯一能真正终止死循环的办法（`已实测`）。
 *
 * ## 为什么 `interrupt()` 不管用
 *
 * ```
 * 实测（Rhino 1.7.15 / JDK 17）：
 *   while(true){} 跑在独立线程
 *   2 秒后调用 interrupt()
 *   → 线程状态仍是 RUNNABLE，脚本继续跑，永远不停
 * ```
 *
 * Rhino 的解释器主循环**不检查中断标志**，只检查指令计数器。
 * 只有 `Context.observeInstructionCount()` 被**真正重写**，每
 * `instructionObserverThreshold` 条指令才有机会抛异常退出。
 *
 * ⚠️ v0.2.0 只调了 `setInstructionObserverThreshold(10_000)` 却**没重写回调**，
 * 等于设了闹钟不接电话 —— 一个 `while(true)` 源就能把 `js-rule`
 * 线程池（2 个线程）永久耗尽。
 *
 * ## 预算怎么传（v0.3.1 修正）
 *
 * `ContextFactory.makeContext()` **拿不到**「这是哪个 JsRuntime 在跑」的信息，
 * 所以不能用工厂上的全局 `defaultBudgetMs`（v0.3.0 就是这么写的，导致
 * `JsRuntime(timeoutMs=600)` 的脚本实际按全局 30s 预算跑）。
 *
 * 正确做法：执行前把预算写进 **ThreadLocal**，`makeContext()` 从同一线程读。
 *
 * ## 外部取消
 *
 * `cancelCurrent()` 必须在**跑脚本的那个线程**上调用才有意义：
 * `Context.getCurrentContext()` 是 thread-local 的。从调用方线程调它什么也拿不到
 * （v0.3.0 的死代码）。这里改成记录 context 引用，
 * 让 `JsRuntime` 能持有它并在外层超时时真正打断。
 */
object InterruptibleContextFactory {

    /** 本次执行的时间预算。由 [JsRuntime] 在 `eval` 前写入，同一线程内可见。 */
    private val budgetHolder = ThreadLocal<Long>()

    /** 本次执行对应的 context，供外层超时后直接打断。 */
    private val currentContextHolder = ThreadLocal<InterruptibleContext?>()

    /**
     * ⚠️ `Context()` 无参构造在 Rhino 1.9+ 被标记为 deprecated，
     * 但 1.7.15（我们钉住的版本，为了 Java 8 字节码兼容）**只有**这一个入口。
     * 换成 `Context(compileTimeAddingCapacity)` 那种带参构造会改变脚本语义。
     * 所以这里显式抑制，并把理由写在这里，免得以后有人「顺手改掉」。
     */
    @Suppress("DEPRECATION")
    class InterruptibleContext(budgetMs: Long) : Context() {
        @Volatile
        var cancelled: Boolean = false

        /**
         * ⚠️ 这三个字段**必须是 var 且能刷新**，不能是构造时定死的 val。
         *
         * 原因：`Context.enter()` 在**同一个线程**上会**复用上次的 Context 实例**
         * （Rhino 的 per-thread 缓存），`makeContext()` 根本不会被再调用。
         * 线程池复用线程时，第二次 `eval` 拿到的是第一次的 Context ——
         * 预算、起始时间全是旧的。
         *
         * 实测（线程池 1 线程，先跑 20s 预算的脚本，再跑 800ms 预算的死循环）：
         *   task2 拿到的预算字段 = 30000   ← 应是 800
         *   → 3 秒未结束，预算完全没生效
         */
        @Volatile
        private var budget: Long = budgetMs

        @Volatile
        private var startedAt: Long = System.currentTimeMillis()

        /** 仅供测试断言：确认预算真的被刷新成这次的。 */
        val debugBudget: Long get() = budget

        /** 每次 `eval` 前由 [enterWithBudget] 调用，把预算和计时重置成本次的。 */
        fun resetBudget(newBudgetMs: Long) {
            budget = newBudgetMs
            startedAt = System.currentTimeMillis()
            cancelled = false
        }

        override fun observeInstructionCount(instructionCount: Int) {
            checkBudget()
        }

        /** `observeInstructionCount` 是 protected，外面调不到，所以留一个可见入口。 */
        fun checkBudget() {
            if (cancelled) throw EvaluatorException("script cancelled")
            if (System.currentTimeMillis() - startedAt > budget) {
                throw EvaluatorException("script timeout (${budget}ms)")
            }
        }

        /** 让外部（外层超时）能立刻打断。 */
        fun cancel() {
            cancelled = true
        }
    }

    private class Factory : ContextFactory() {
        override fun makeContext(): Context {
            val budget = budgetHolder.get() ?: DEFAULT_BUDGET_MS
            val ctx = InterruptibleContext(budget)
            currentContextHolder.set(ctx)
            return ctx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            (cx as? InterruptibleContext)?.checkBudget()
        }
    }

    const val DEFAULT_BUDGET_MS: Long = 5000L

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        ContextFactory.initGlobal(Factory())
        installed = true
    }

    /**
     * 在**即将跑脚本的线程**上设置预算，并返回一个可用的 Context。
     *
     * 调用方负责 `Context.exit()`。
     */
    fun enterWithBudget(budgetMs: Long): InterruptibleContext {
        install()
        budgetHolder.set(budgetMs)
        val ctx = Context.enter() as InterruptibleContext
        // ★ 关键：enter() 可能返回**同线程上次残留的实例**，预算和计时必须强制重置。
        //   只靠 makeContext() 读 ThreadLocal 不够 —— 那条路径在复用线程时根本不会走。
        ctx.resetBudget(budgetMs)
        ctx.setInstructionObserverThreshold(10_000)
        currentContextHolder.set(ctx)
        return ctx
    }

    /** 退出并把 ThreadLocal 清掉，避免线程复用串预算。 */
    fun exitClean() {
        budgetHolder.remove()
        currentContextHolder.remove()
        Context.exit()
    }

    /**
     * 打断**当前线程**正在跑的脚本。
     * 只能从脚本所在线程调 —— 这也是 `JsRuntime` 内部用的路径。
     */
    fun cancelCurrent() {
        currentContextHolder.get()?.cancel()
    }

    /** 供外层超时使用：直接持有 context 引用后取消。 */
    fun cancel(ctx: InterruptibleContext?) {
        ctx?.cancel()
    }
}
