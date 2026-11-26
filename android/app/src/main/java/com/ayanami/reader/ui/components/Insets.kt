package com.ayanami.reader.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer

/**
 * 顶到状态栏下方用的安全间距。
 *
 * ### 为什么需要它
 *
 * Activity 开了 `enableEdgeToEdge()` —— 内容会画到状态栏底下（这是对的，
 * 状态栏透明时背景色能连成一片）。但**页面自身必须自己让出状态栏高度**，
 * 否则标题会被状态栏图标压住。
 *
 * 之前各页面都写死 `Modifier.height(24.dp)`：在状态栏高度恰好 24dp 的机器上
 * 看着没问题，换个机型就重叠。实测在 density 420 的模拟器上，
 * 「MOD」标题就和状态栏时钟叠在一起了。
 *
 * 用 insets 而不是固定值，才是唯一正确的做法。
 */
@Composable
fun StatusBarSpacer(extra: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Spacer(Modifier.height(top + extra))
}

/** 给整页用的顶部安全 padding（`Modifier.padding(top = ...)` 的替代）。 */
@Composable
fun statusBarTopPadding(): androidx.compose.ui.unit.Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
