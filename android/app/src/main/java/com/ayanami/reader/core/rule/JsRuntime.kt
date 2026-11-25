package com.ayanami.reader.core.rule

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * `@js` 的 JS 宿主 —— 对应 Legado 的 `JsExtensions` + `RhinoExtensions`。
 *
 * 三个刻意的设计决定（都来自实测，不是猜的）：
 *
 * 1. **只绑定 62 条样本里真实用到的 API**
 *    `java.base64`(25) `java.ajax`(14) `java.get`(14) `java.getString`(6)
 *    `java.encodeURI`(6) `java.put`(4) `java.getElements`(3) `java.log`(10)
 *    → 这 8 个覆盖了全部真实调用。`Packages.*` 仍禁用（安全策略，只损失 1/62 源）。
 *
 * 2. **超时靠 `observeInstructionCount` 真中断，不是丢弃线程**
 *    实测过：`Thread.interrupt()` 对 `while(true){}` **无效**（线程一直 RUNNABLE），
 *    单纯 `future.cancel(true)` 也只是把 Future 标记成已取消，脚本仍在跑。
 *    真正管用的是重写 `Context.observeInstructionCount()` 并抛异常
 *    —— 见 [InterruptibleContextFactory]，实测能拿到 `TERMINATED`。
 *    仍保留外层 `future.get(timeout)` 作为双保险（覆盖 JS 之外的长耗时）。
 *
 * 3. **编译结果要缓存**
 *    `CompiledScript` 复用能把 3000 章的重复编译开销降下来。
 *    方案 §4.5 提到「每章新实例 = 缓存归零」，这里的缓存按实例存。
 */
class JsRuntime(
    private val bridge: JsBridge,
    private val timeoutMs: Long = 5000L
) {

    /** 编译缓存：同一段脚本串只编译一次。 */
    private val scriptCache = HashMap<String, org.mozilla.javascript.Script>()

    /** `java.get/@put` 共享的变量表。 */
    private val vars = HashMap<String, Any?>()

    /** 记录本次执行是否命中了不支持的 API。 */
    var lastUnsupported: String? = null

    fun bindVar(key: String, value: Any?) {
        vars[key] = value
    }

    fun getVar(key: String): Any? = vars[key]

    /**
     * 执行一段脚本。
     * @return 结果字符串；抛异常或超时时返回 null 并把原因写进 [lastError]
     */
    fun eval(script: String, content: Any? = null, baseUrl: String = ""): String? {
        lastError = null
        lastUnsupported = null

        // 让外层超时能真的打断内层 —— 存下这次执行用的 context
        val runningCtx = java.util.concurrent.atomic.AtomicReference<
            InterruptibleContextFactory.InterruptibleContext?>(null)

        val future = pool.submit<Result<String>> {
            try {
                Result.success(runInScope(script, content, baseUrl, runningCtx))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

        return try {
            val r = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            r.fold(
                onSuccess = { it },
                onFailure = { e ->
                    lastError = when {
                        e is StackOverflowError -> RuleError.JsThrown("脚本递归过深")
                        e.message?.contains("script timeout") == true ->
                            RuleError.JsTimeout
                        e.message?.contains("script cancelled") == true ->
                            RuleError.JsTimeout
                        else -> RuleError.JsThrown(e.message ?: e.javaClass.simpleName)
                    }
                    null
                }
            )
        } catch (e: TimeoutException) {
            // 外层兜底：说明脚本卡在 JS 之外（比如 java.ajax 长阻塞），
            // 或者内层预算被设得比这里更大。直接打断那个 context。
            InterruptibleContextFactory.cancel(runningCtx.get())
            future.cancel(true)
            aborted.incrementAndGet()
            lastError = RuleError.JsTimeout
            null
        }
    }

    var lastError: RuleError? = null

    /** 被放弃的线程数，用于监控泄漏。 */
    val abandoned: AtomicInteger get() = aborted
    private val aborted = AtomicInteger(0)

    private fun runInScope(
        script: String,
        content: Any?,
        baseUrl: String,
        ctxHolder: java.util.concurrent.atomic.AtomicReference<InterruptibleContextFactory.InterruptibleContext?>
    ): String {
        // 预算跟着这次执行走，不用工厂上的全局值（v0.3.0 的 bug）
        val ctx = InterruptibleContextFactory.enterWithBudget(timeoutMs)
        ctxHolder.set(ctx)
        try {
            ctx.optimizationLevel = -1          // 解释模式：Android 上不需要生成字节码
            ctx.languageVersion = Context.VERSION_ES6

            val scope = ctx.initSafeStandardObjects()
            val binder = JsBinder(ctx, scope, bridge, vars, this, content, baseUrl)
            ScriptableObject.putProperty(scope, "java", binder.buildJavaObject())

            val compiled = scriptCache.getOrPut(script) {
                ctx.compileString(script, "rule", 1, null)
            }
            val result = compiled.exec(ctx, scope)
            // 脚本里裸写 `result = 'x'` 是挂到 scope 上的全局，不是返回值
            val scopeResult = ScriptableObject.getProperty(scope, "result")
            if (scopeResult != null && scopeResult !== Scriptable.NOT_FOUND) {
                val s = stringify(scopeResult)
                if (s.isNotEmpty()) return s
            }
            return stringify(result)
        } finally {
            InterruptibleContextFactory.exitClean()
        }
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> ""
        is org.mozilla.javascript.Undefined -> ""
        is org.mozilla.javascript.NativeJavaObject -> {
            val u = v.unwrap()
            if (u == null) "" else u.toString()
        }
        is org.mozilla.javascript.NativeArray -> {
            val n = v.length.toInt()
            (0 until n).joinToString("\n") { i ->
                stringify(ScriptableObject.getProperty(v, i.toInt()))
            }
        }
        is org.mozilla.javascript.Scriptable -> Context.toString(v)
        else -> v.toString()
    }

    private companion object {
        /**
         * 有界线程池：同时最多 2 个脚本，超时后线程会被放弃但不会无限增长。
         * 用 daemon 线程，进程退出不被拖住。
         */
        val pool: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "js-rule").apply { isDaemon = true }
        }
    }
}

/**
 * 抓取能力注入点 —— 让 JsRuntime 不直接依赖网络层，
 * 这样纯 JVM 单测里可以喂假响应。
 */
interface JsBridge {
    /** `java.ajax(url)` */
    fun ajax(url: String, headers: Map<String, String>? = null): String?

    /** `java.connect(url)` 返回可读文本（首版与 ajax 同实现） */
    fun connect(url: String, headers: Map<String, String>? = null): String? = ajax(url, headers)

    /** `java.log(msg)` */
    fun log(msg: String)

    /** `java.toast` / `java.longToast` —— 首版落到 log */
    fun toast(msg: String) = log(msg)

    /** `java.base64Encode` / `Decode` */
    fun base64Encode(data: String, flags: Int = 0): String
    fun base64Decode(data: String, flags: Int = 0): String

    /** `java.encodeURI` */
    fun encodeUri(s: String, enc: String = "UTF-8"): String

    /** 当前阶段的内容（供 `java.getString(rule)` 用） */
    fun currentContent(): Any?

    /** `java.getElement(rule)` / `getElements(rule)` —— 返回 jsoup Element 列表 */
    fun getElements(rule: String): List<org.jsoup.nodes.Element>

    /** 让 java.put 写的变量能回到规则引擎 */
    fun putVar(key: String, value: String)
    fun getVar(key: String): String?
}

/** 把 `java.*` 组装成一个 Rhino 对象。 */
private class JsBinder(
    private val ctx: Context,
    private val scope: Scriptable,
    private val bridge: JsBridge,
    private val vars: MutableMap<String, Any?>,
    private val runtime: JsRuntime,
    private val content: Any?,
    private val baseUrl: String
) {

    fun buildJavaObject(): ScriptableObject {
        val obj = ctx.newObject(scope) as ScriptableObject

        fun fn(name: String, impl: (Array<Any?>) -> Any?) {
            val f = object : org.mozilla.javascript.BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>
                ): Any? = impl(args.toList().toTypedArray())
            }
            ScriptableObject.putProperty(obj, name, f)
        }

        // ---- 网络 ----
        fn("ajax") { args -> bridge.ajax(str(args, 0), headersArg(args, 1)) ?: "" }
        fn("connect") { args -> bridge.connect(str(args, 0), headersArg(args, 1)) ?: "" }

        // ---- 日志 ----
        fn("log") { args ->
            bridge.log(
                (args.indices).joinToString(" ") { i -> str(args, i) }
            )
            ""
        }
        fn("toast") { args -> bridge.toast(str(args, 0)); "" }
        fn("longToast") { args -> bridge.toast(str(args, 0)); "" }

        // ---- 编解码 ----
        fn("base64Encode") { args -> bridge.base64Encode(str(args, 0), int(args, 1, 0)) }
        fn("base64Decode") { args -> bridge.base64Decode(str(args, 0), int(args, 1, 0)) }
        fn("encodeURI") { args ->
            bridge.encodeUri(str(args, 0), strOr(args, 1, "UTF-8"))
        }
        fn("encodeURIComponent") { args ->
            bridge.encodeUri(str(args, 0), strOr(args, 1, "UTF-8"))
        }

        // ---- 变量 ----
        fn("put") { args ->
            val k = str(args, 0)
            val v = str(args, 1)
            vars[k] = v
            bridge.putVar(k, v)
            ""
        }
        fn("get") { args ->
            val k = str(args, 0)
            vars[k] ?: bridge.getVar(k) ?: ""
        }

        // ---- 规则取值 ----
        fn("getString") { args ->
            val rule = str(args, 0)
            val analyzer = AnalyzeRule().apply {
                val c = content ?: bridge.currentContent()
                when (c) {
                    is String -> setContent(c, baseUrl)
                    is org.jsoup.nodes.Element -> setContent(c, baseUrl)
                    is org.jsoup.nodes.Document -> setContent(c, baseUrl)
                    else -> Unit
                }
            }
            analyzer.getString(rule) ?: ""
        }
        fn("getElement") { args ->
            val list = bridge.getElements(str(args, 0))
            list.firstOrNull()
        }
        fn("getElements") { args ->
            val list = bridge.getElements(str(args, 0))
            ctx.newArray(scope, list.toTypedArray())
        }

        // ---- 明确不支持的 API ----
        // 显式报出来，不静默返回空（方案 §3.2 的要求）
        fn("startBrowser") { _ ->
            runtime.lastUnsupported = "startBrowser"
            throw RuntimeException("不支持 java.startBrowser（首版无 WebView）")
        }
        fn("webView") { _ ->
            runtime.lastUnsupported = "webView"
            throw RuntimeException("不支持 java.webView（首版无 WebView）")
        }
        fn("startBrowserAwait") { _ ->
            runtime.lastUnsupported = "startBrowserAwait"
            throw RuntimeException("不支持 java.startBrowserAwait")
        }

        // ---- 常量 ----
        ScriptableObject.putProperty(obj, "base64FlagDefault", 0)
        ScriptableObject.putProperty(obj, "base64FlagNoWrap", 2)
        ScriptableObject.putProperty(obj, "base64FlagUrlSafe", 8)
        ScriptableObject.putProperty(obj, "base64FlagNoPadding", 1)

        return obj
    }

    private fun str(args: Array<out Any?>, i: Int): String {
        val v = args.getOrNull(i) ?: return ""
        return when (v) {
            is org.mozilla.javascript.Undefined -> ""
            is org.mozilla.javascript.NativeJavaObject -> v.unwrap()?.toString() ?: ""
            else -> v.toString()
        }
    }

    private fun strOr(args: Array<out Any?>, i: Int, def: String): String =
        str(args, i).ifBlank { def }

    private fun int(args: Array<out Any?>, i: Int, def: Int): Int =
        str(args, i).toDoubleOrNull()?.toInt() ?: def

    @Suppress("UNCHECKED_CAST")
    private fun headersArg(args: Array<out Any?>, i: Int): Map<String, String>? {
        val v = args.getOrNull(i) ?: return null
        if (v is org.mozilla.javascript.Undefined || v is org.mozilla.javascript.NativeJavaObject) return null
        if (v !is Scriptable) return null
        val out = HashMap<String, String>()
        for (id in v.ids) {
            val key = id.toString()
            out[key] = Context.toString(ScriptableObject.getProperty(v, key))
        }
        return out.ifEmpty { null }
    }
}
