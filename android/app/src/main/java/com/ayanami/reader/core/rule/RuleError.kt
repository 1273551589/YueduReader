package com.ayanami.reader.core.rule

/**
 * 引擎错误枚举 —— 差分/体检只比这一层（方案 §4.3 B）。
 *
 * 与 UI 状态分开：UI 只有 6 个给人看的值，这里是给程序比的。
 */
sealed class RuleError {
    /** 连接/读取超时。 */
    data class NetworkTimeout(val stage: String) : RuleError()

    /** DNS 失败 / TCP 拒绝 / TLS 错。 */
    data class NetworkRefused(val message: String) : RuleError()

    /** 拿到响应但 4xx/5xx。 */
    data class HttpStatus(val code: Int) : RuleError()

    /** 规则跑通但结果为空。 */
    data class SelectorEmpty(val stage: String) : RuleError()

    /** 规则本身解析不了。 */
    data class RuleSyntaxError(val rule: String) : RuleError()

    /** `@js` 抛异常。 */
    data class JsThrown(val message: String) : RuleError()

    /** JS 求值超时。 */
    object JsTimeout : RuleError()

    /**
     * 当前层不支持的能力。
     * 首版 **不支持 `@js`**，命中时必须显式返回这个，
     * 不能静默返回空串（方案 §3.2 的要求）。
     */
    data class UnsupportedApi(val name: String) : RuleError()

    /** 当前层不覆盖该能力 —— 既不算成功也不算失败。 */
    data class SkippedStage(val reason: String) : RuleError()

    /** 检测到登录墙 / 人机验证。 */
    data class RequiresLogin(val evidence: String) : RuleError()

    /** 编码错 / 压缩错 / 格式不符。 */
    data class DecodeError(val message: String) : RuleError()

    /**
     * 正则匹配超时（灾难性回溯）。方案 §3.3.2 新增的枚举。
     * 注意：这**不是** `JsTimeout`，两者触发条件完全不同。
     */
    data class RegexTimeout(val pattern: String) : RuleError()

    /** 出错的那条规则串（能拿到时）。UI 用来告诉用户"该改哪里"。 */
    val offendingRule: String?
        get() = when (this) {
            is RuleSyntaxError -> rule
            is RegexTimeout -> pattern
            else -> null
        }

    /** 出错阶段（能拿到时）。避免和子类字段重名，故叫 stageHint。 */
    val stageHint: String?
        get() = when (this) {
            is NetworkTimeout -> this.stage
            is SelectorEmpty -> this.stage
            is SkippedStage -> this.reason
            else -> null
        }

    val humanMessage: String
        get() = when (this) {
            is NetworkTimeout -> "网络超时（$stage）"
            is NetworkRefused -> "连接失败：$message"
            is HttpStatus -> "HTTP $code"
            is SelectorEmpty -> "未取到内容（$stage）"
            is RuleSyntaxError -> "规则语法错误"
            is JsThrown -> "脚本异常：$message"
            is JsTimeout -> "脚本超时"
            is UnsupportedApi -> "不支持的能力：$name"
            is SkippedStage -> "跳过（$reason）"
            is RequiresLogin -> "需要登录或验证"
            is DecodeError -> "解码错误：$message"
            is RegexTimeout -> "正则超时（可能有灾难性回溯）"
        }

    /** 5xx 可重试，4xx 不可（403/429 明确拒绝，重试等于挑拨）。 */
    val retryable: Boolean
        get() = when (this) {
            is NetworkTimeout -> true
            is HttpStatus -> code >= 500
            else -> false
        }
}

/**
 * 可序列化的错误快照。
 *
 * ⚠️ `RuleError` 是 sealed class，**Gson 无法直接往返**：
 * 写出去是 `{"stage":"toc.chapterList"}`，读回来因为抽象类不能实例化，
 * 结果是**整个外层对象返回 null**。
 *
 * 实测后果（v0.3.4 之前）：只要任何一个书源的体检结果带 error，
 * `Map<String, CheckResult>` 整体反序列化失败 → **所有书源的体检结果全部丢失**，
 * 包括那些"抽样可读"的。重启后界面上一个绿标都不剩。
 *
 * 所以持久化时用这个扁平结构，读回来再 [toRuleError] 还原。
 */
data class RuleErrorSnapshot(
    /**
     * ⚠️ 声明为 `String?` 而不是 `String` —— 不是笔误。
     *
     * Gson 反序列化会**绕过 Kotlin 的空检查**直接塞 null。旧版本写出的文件里
     * `error` 是 `{"stage":"toc.chapterList"}`，没有 `kind` 字段，
     * 如果这里声明成非空的 `String`，`when(kind)` 会去调 `kind.hashCode()` → NPE。
     *
     * 实测（升级用户场景）：NPE 发生在 `toRuleError()`，
     * 抛出位置在 `PersistedCheckResult.toResult()` → **整个读取流程崩掉**。
     */
    val kind: String? = null,
    /** 主参数：stage / rule / code / name / pattern / message */
    val arg: String? = null,
    val code: Int? = null
) {
    fun toRuleError(): RuleError = when (kind) {
        "NetworkTimeout" -> RuleError.NetworkTimeout(arg ?: "")
        "NetworkRefused" -> RuleError.NetworkRefused(arg ?: "")
        "HttpStatus" -> RuleError.HttpStatus(code ?: 0)
        "SelectorEmpty" -> RuleError.SelectorEmpty(arg ?: "")
        "RuleSyntaxError" -> RuleError.RuleSyntaxError(arg ?: "")
        "JsThrown" -> RuleError.JsThrown(arg ?: "")
        "JsTimeout" -> RuleError.JsTimeout
        "UnsupportedApi" -> RuleError.UnsupportedApi(arg ?: "")
        "SkippedStage" -> RuleError.SkippedStage(arg ?: "")
        "RequiresLogin" -> RuleError.RequiresLogin(arg ?: "")
        "DecodeError" -> RuleError.DecodeError(arg ?: "")
        "RegexTimeout" -> RuleError.RegexTimeout(arg ?: "")
        else -> RuleError.DecodeError("未知错误类型：$kind")
    }

    companion object {
        fun from(e: RuleError?): RuleErrorSnapshot? = when (e) {
            null -> null
            is RuleError.NetworkTimeout -> RuleErrorSnapshot("NetworkTimeout", e.stage)
            is RuleError.NetworkRefused -> RuleErrorSnapshot("NetworkRefused", e.message)
            is RuleError.HttpStatus -> RuleErrorSnapshot("HttpStatus", code = e.code)
            is RuleError.SelectorEmpty -> RuleErrorSnapshot("SelectorEmpty", e.stage)
            is RuleError.RuleSyntaxError -> RuleErrorSnapshot("RuleSyntaxError", e.rule)
            is RuleError.JsThrown -> RuleErrorSnapshot("JsThrown", e.message)
            is RuleError.JsTimeout -> RuleErrorSnapshot("JsTimeout")
            is RuleError.UnsupportedApi -> RuleErrorSnapshot("UnsupportedApi", e.name)
            is RuleError.SkippedStage -> RuleErrorSnapshot("SkippedStage", e.reason)
            is RuleError.RequiresLogin -> RuleErrorSnapshot("RequiresLogin", e.evidence)
            is RuleError.DecodeError -> RuleErrorSnapshot("DecodeError", e.message)
            is RuleError.RegexTimeout -> RuleErrorSnapshot("RegexTimeout", e.pattern)
        }
    }
}
