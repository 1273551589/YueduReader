package com.ayanami.reader.core.rule

import android.util.Base64

/**
 * `android.util.Base64` 的转发层。
 *
 * ⚠️ 这里**不自己实现** —— 在 Android App 里可以直接用真品，
 * 保真问题自然消失。这正是「跑在 Android 上」相对「纯 JVM 服务端」的一个便宜优势。
 *
 * `shim-fidelity/` 里那 49 个用例的结论（朴素替代 40/49 不一致、解码器极度宽容）
 * 只在**非 Android 环境**下才需要照 AOSP 移植。首版是 App，直接用系统实现。
 *
 * 不解码失败抛异常 —— Android 的解码器对非法字符是静默跳过，
 * 调用方（JS）拿到空串比拿到异常更接近原版行为。
 */
object AndroidBase64Compat {

    /** flags 与 android.util.Base64 一致：DEFAULT=0 / NO_PADDING=1 / NO_WRAP=2 / URL_SAFE=8 / CRLF=4 */
    fun encode(data: ByteArray, flags: Int = 0): String =
        runCatching { Base64.encodeToString(data, flags) }.getOrDefault("")

    fun decode(data: String, flags: Int = 0): ByteArray =
        runCatching { Base64.decode(data, flags) }.getOrDefault(ByteArray(0))

    fun encodeString(s: String, flags: Int = 0): String =
        encode(s.toByteArray(Charsets.UTF_8), flags)

    fun decodeToString(s: String, flags: Int = 0): String =
        runCatching { String(decode(s, flags), Charsets.UTF_8) }.getOrDefault("")
}
