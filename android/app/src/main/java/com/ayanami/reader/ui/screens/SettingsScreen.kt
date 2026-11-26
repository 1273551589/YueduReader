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
            // ⚠️ v0.6.0 这里两个「配置」按钮都是空 lambda —— 点了什么都不发生。
            // 现在做成真的能填的输入框（弹窗内联展开，不跳页面）。
            SecretField(
                label = "PHPSESSID",
                hint = "浏览器登录 pixiv.net 后，从 Cookie 里复制 PHPSESSID 的值",
                value = s.pixivCookie,
                onChange = { s.pixivCookie = it; vm.notifySettingsChanged() }
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            SecretField(
                label = "代理地址",
                hint = "http://192.168.1.10:7890 或 socks5://192.168.1.10:1080；留空直连",
                value = s.proxyUrl,
                onChange = { s.proxyUrl = it; vm.notifySettingsChanged() },
                plain = true
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))

            // ---- 图床镜像 ----
            Text(
                "图片镜像",
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "图床 i.pximg.net 必须有 Referer 才能读，国内网络常直连失败。" +
                    "换反代域名可解决裂图。",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                com.ayanami.reader.core.pixiv.PixivImageUrls.KNOWN_MIRRORS.forEach { host ->
                    val on = s.pixivImageMirror == host
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) t.primary else t.textSecondary.copy(alpha = 0.10f))
                            .clickable {
                                s.pixivImageMirror = host
                                vm.notifySettingsChanged()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (host == com.ayanami.reader.core.pixiv.PixivImageUrls.OFFICIAL_HOST)
                                "官方直连" else host,
                            fontSize = 11.sp,
                            color = if (on) Color.White else t.textPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "当前：${s.pixivImageMirror}",
                style = MaterialTheme.typography.bodySmall,
                color = t.primary
            )

            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))

            // ---- API 镜像 ----
            SecretField(
                label = "API 镜像（可选）",
                hint = "自建反代时填，例如 https://px.example.com；留空用官方 pixiv.net",
                value = s.pixivApiBase,
                onChange = { s.pixivApiBase = it; vm.notifySettingsChanged() },
                plain = true
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "免登录可直接看：插画日/周/月榜、热门标签、作品详情、小说正文、搜索。\n" +
                    "R18 榜单与个人收藏需要填 PHPSESSID。\n" +
                    "直连被阻断时（TLS 中断/超时），填代理或换图片镜像。",
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                color = t.textSecondary
            )
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

/**
 * 可折叠的密钥/地址输入行。
 *
 * 默认收起只显示「已配置 / 未配置」，点开才是输入框 ——
 * 这类字段用户一辈子只填一次，不该长期占着屏幕高度。
 *
 * @param plain 是否明文显示。代理地址不是秘密，明文更易核对；
 *   Cookie 默认隐藏，点「显示」才看。
 */
@Composable
private fun SecretField(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    plain: Boolean = false
) {
    val t = LocalReaderTokens.current
    var open by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = t.textPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        value.isBlank() -> "未配置"
                        plain -> value
                        else -> "已配置（${value.take(4)}…${value.takeLast(2)}）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value.isBlank()) t.textSecondary else t.primary
                )
            }
            ArrowButton(if (open) "收起" else "配置") { open = !open }
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint, fontSize = 12.sp, color = t.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                visualTransformation = if (plain || reveal) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                trailingIcon = if (plain) null else {
                    {
                        Text(
                            if (reveal) "隐藏" else "显示",
                            fontSize = 11.sp,
                            color = t.primary,
                            modifier = Modifier
                                .clickable { reveal = !reveal }
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary.copy(alpha = 0.8f))
        }
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
