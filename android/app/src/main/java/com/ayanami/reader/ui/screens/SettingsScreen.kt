package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.StatusBarSpacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.data.Settings
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.adultEnabled
import com.ayanami.reader.ui.components.DividerLine
import com.ayanami.reader.ui.components.ScreenHeader
import com.ayanami.reader.ui.setAdultEnabled
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * 设置中心 —— 收口所有全局配置。
 *
 * 之前书源管理、MOD 管理散落在书架页的入口卡片上，
 * 而 R18 / 字号 / 阅读主题这些根本没有入口。全部收到这里。
 *
 * 分区：
 * 1. 内容安全（R18 总开关 —— 最重要，放最上面）
 * 2. 书源与 MOD
 * 3. 阅读器
 * 4. Pixiv
 */
@Composable
fun SettingsScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onSources: () -> Unit,
    onMods: () -> Unit
) {
    val t = LocalReaderTokens.current
    val scroll = rememberScrollState()
    // 读设置计数以响应变化
    vm.settingsTick()
    val s = vm.settings

    val readable = state.checks.values.count { it.status == SourceChecker.UiStatus.READABLE }
    val failed = state.checks.values.count {
        it.status != SourceChecker.UiStatus.READABLE &&
            it.status != SourceChecker.UiStatus.UNVERIFIED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.background)
            .verticalScroll(scroll)
    ) {
        StatusBarSpacer()
        ScreenHeader(title = "设置", subtitle = "全部配置都在这里")

        // ================= 内容安全 =================
        SectionTitle("内容安全")
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "R18 内容",
                        style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "控制书城的成人向分类、内容分级筛选与 Pixiv 的 R18 榜单。\n" +
                            "关闭时成人内容会被硬过滤，不只是隐藏入口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = vm.adultEnabled,
                    onCheckedChange = { vm.setAdultEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = t.primary)
                )
            }
            if (vm.adultEnabled) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFB0468C).copy(alpha = 0.09f))
                        .padding(10.dp)
                ) {
                    Text(
                        "已开启。书城分类会多出成人向细分（母子 / NTR / 百合 / 调教 / 人妻熟女）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0468C)
                    )
                }
            }
        }

        // ================= 书源与 MOD =================
        SectionTitle("书源与 MOD")
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${state.sources.size} 个书源",
                        style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "$readable 个抽样可读" + if (failed > 0) " · $failed 个失败" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary
                    )
                }
                ArrowButton("管理", onClick = onSources)
            }
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${vm.mods.mods.size} 个 MOD",
                        style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (vm.modErrors.isEmpty()) "声明式扩展，零代码执行"
                        else "${vm.modErrors.size} 个加载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vm.modErrors.isEmpty()) t.textSecondary else t.accent
                    )
                }
                ArrowButton("管理", onClick = onMods)
            }
        }

        // ================= 阅读器 =================
        SectionTitle("阅读器")

        // 字号
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("字号", style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text("${s.fontSize} sp", style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary)
                }
                Stepper(
                    value = s.fontSize,
                    min = Settings.MIN_FONT,
                    max = Settings.MAX_FONT,
                    onChange = { s.fontSize = it; vm.notifySettingsChanged() }
                )
            }
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("行距", style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        String.format("%.2f", s.lineHeightPercent / 100f) + " 倍",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary
                    )
                }
                Stepper(
                    value = s.lineHeightPercent,
                    min = 120,
                    max = 240,
                    step = 10,
                    onChange = { s.lineHeightPercent = it; vm.notifySettingsChanged() }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 阅读主题
        SettingCard {
            Text("背景主题", style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.ReaderTheme.entries.forEach { mode ->
                    ChoicePill(
                        label = mode.label,
                        selected = s.readerTheme == mode,
                        onClick = { s.readerTheme = mode; vm.notifySettingsChanged() }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("翻页方式", style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.PageMode.entries.forEach { mode ->
                    ChoicePill(
                        label = mode.label,
                        selected = s.pageMode == mode,
                        onClick = { s.pageMode = mode; vm.notifySettingsChanged() }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动恢复进度", style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text("打开书籍时跳到上次读到的章节",
                        style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                }
                Switch(
                    checked = s.restoreProgress,
                    onCheckedChange = { s.restoreProgress = it; vm.notifySettingsChanged() },
                    colors = SwitchDefaults.colors(checkedTrackColor = t.primary)
                )
            }
        }

        // ================= Pixiv =================
        SectionTitle("Pixiv")
        SettingCard {
            Text(
                "热榜与图片需要 Cookie 或 Refresh Token。留空则只能看公开热门。",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PHPSESSID", style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (s.pixivCookie.isBlank()) "未配置" else "已配置（${s.pixivCookie.take(6)}…）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.pixivCookie.isBlank()) t.textSecondary else t.primary
                    )
                }
                ArrowButton("配置", onClick = { /* 后续版本弹输入框 */ })
            }
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("代理", style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        s.proxyUrl.ifBlank { "直连（无代理）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary
                    )
                }
                ArrowButton("配置", onClick = { /* 后续版本 */ })
            }
        }

        // ================= 关于 =================
        SectionTitle("关于")
        SettingCard {
            // 版本号从 BuildConfig 读，不写死 ——
            // 之前硬编码成 "v0.5.0"，升到 0.6.0 后界面还在显示旧版本
            Text(
                "YueduReader v${com.ayanami.reader.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "纯本地阅读器，无后端服务器。书源是你自己导入的，规则在本地执行。\n" +
                    "Legado 兼容引擎：默认选择器 / @ 链式下钻 / @js / 分页 / GBK 编码。",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun SectionTitle(text: String) {
    val t = LocalReaderTokens.current
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = t.textSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 7.dp)
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(t.cornerRadius))
            .background(t.surface)
            .padding(15.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun ArrowButton(label: String, onClick: () -> Unit) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(t.primary.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = t.primary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) t.primary else t.textSecondary.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) Color.White else t.textPrimary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/** 加减步进器。 */
@Composable
private fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onChange: (Int) -> Unit
) {
    val t = LocalReaderTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = value > min) { onChange((value - step).coerceAtLeast(min)) }
        Box(
            modifier = Modifier.width(46.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (step == 1) "$value" else String.format("%.1f", value / 100f),
                style = MaterialTheme.typography.bodyMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        StepButton("+", enabled = value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(t.textSecondary.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = if (enabled) t.textPrimary else t.textSecondary.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium
        )
    }
}
