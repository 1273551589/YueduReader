package com.ayanami.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.data.Settings

/**
 * 阅读页的配色。
 *
 * 不能直接复用 App 主题色 —— 阅读页需要「近乎纯色的纸」，
 * 而主界面用的是卡片式浅底。护眼模式尤其不能沿用主界面，否则眼睛会累。
 */
data class ReaderPaper(
    val bg: Color,
    val fg: Color,
    val accent: Color
)

fun readerPaper(theme: Settings.ReaderTheme): ReaderPaper = when (theme) {
    // 日间：接近纯白，正文用近黑（不是纯 #000，那样对比过强会刺眼）
    Settings.ReaderTheme.DAY -> ReaderPaper(
        bg = Color(0xFFFDFCFA),
        fg = Color(0xFF1C1C1E),
        accent = Color(0xFF3B6FE0)
    )
    // 护眼：米黄纸 + 深棕字，模拟纸质书
    Settings.ReaderTheme.EYE_CARE -> ReaderPaper(
        bg = Color(0xFFF3E9D2),
        fg = Color(0xFF3D3527),
        accent = Color(0xFF8A6A3A)
    )
    // 夜间：深灰而非纯黑（纯黑配白字在 OLED 上会有拖影）
    Settings.ReaderTheme.NIGHT -> ReaderPaper(
        bg = Color(0xFF16181C),
        fg = Color(0xFFC9CBD0),
        accent = Color(0xFF6D9CFF)
    )
}

/**
 * 阅读页底部快捷设置面板。
 *
 * ### 为什么必须在阅读页里
 *
 * v0.6.0 调字号要退出阅读器 → 进设置 Tab → 改 → 再找回来。
 * 中途还会丢失当前读到哪，这是最劝退的交互之一。
 *
 * 这里把四个最常改的东西直接摊开：字号、行距、纸色、翻页方式。
 * 拨一下立刻生效（`onChange` 直接写 Settings + 触发重组），不点确定。
 */
@Composable
fun ReaderQuickPanel(
    settings: Settings,
    paper: ReaderPaper,
    onChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ⚠️ 面板底色必须是**不透明**的。之前用 paper.fg.copy(alpha = 0.06f)，
    // 结果正文从面板底下透出来，文字叠文字完全没法读（模拟器实测截图确认）。
    // 用纸色和正文色的混合做一个比纸稍深的实色，既区分层次又不刺眼。
    val panelBg = lerp(paper.bg, paper.fg, 0.10f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(panelBg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 顶部一条细分割线，明确"底下是正文、这里是控件"
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paper.fg.copy(alpha = 0.12f))
        )
        Spacer(Modifier.height(12.dp))
        // ---- 字号 ----
        PanelRow(label = "字号", fg = paper.fg) {
            Stepper(
                value = "${settings.fontSize}",
                enabledMinus = settings.fontSize > Settings.MIN_FONT,
                enabledPlus = settings.fontSize < Settings.MAX_FONT,
                onMinus = {
                    settings.fontSize = settings.fontSize - 1
                    onChange()
                },
                onPlus = {
                    settings.fontSize = settings.fontSize + 1
                    onChange()
                },
                paper = paper
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- 行距 ----
        PanelRow(label = "行距", fg = paper.fg) {
            Stepper(
                value = "${settings.lineHeightPercent}%",
                enabledMinus = settings.lineHeightPercent > 120,
                enabledPlus = settings.lineHeightPercent < 240,
                onMinus = {
                    settings.lineHeightPercent = settings.lineHeightPercent - 10
                    onChange()
                },
                onPlus = {
                    settings.lineHeightPercent = settings.lineHeightPercent + 10
                    onChange()
                },
                paper = paper
            )
        }
        Spacer(Modifier.height(14.dp))

        // ---- 纸色 ----
        PanelRow(label = "纸色", fg = paper.fg) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.ReaderTheme.entries.forEach { th ->
                    val on = settings.readerTheme == th
                    val swatch = readerPaper(th)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) paper.accent else paper.fg.copy(alpha = 0.08f))
                            .clickable {
                                settings.readerTheme = th
                                onChange()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(swatch.bg)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                th.label,
                                fontSize = 12.sp,
                                color = if (on) Color.White else paper.fg,
                                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- 翻页方式 ----
        PanelRow(label = "翻页", fg = paper.fg) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.PageMode.entries.forEach { m ->
                    val on = settings.pageMode == m
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) paper.accent else paper.fg.copy(alpha = 0.08f))
                            .clickable {
                                settings.pageMode = m
                                onChange()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            m.label,
                            fontSize = 12.sp,
                            color = if (on) Color.White else paper.fg,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRow(label: String, fg: Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = fg.copy(alpha = 0.65f),
            modifier = Modifier.width(48.dp)
        )
        content()
    }
}

@Composable
private fun Stepper(
    value: String,
    enabledMinus: Boolean,
    enabledPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    paper: ReaderPaper
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabledMinus, paper.accent, paper.fg, onMinus)
        Text(
            value,
            fontSize = 13.sp,
            color = paper.fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        StepButton("+", enabledPlus, paper.accent, paper.fg, onPlus)
    }
}

@Composable
private fun StepButton(
    label: String,
    enabled: Boolean,
    accent: Color,
    fg: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) accent.copy(alpha = 0.14f) else fg.copy(alpha = 0.05f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = if (enabled) accent else fg.copy(alpha = 0.25f),
            fontWeight = FontWeight.Medium
        )
    }
}

/** 阅读页顶部进度条（页内百分比）。 */
@Composable
fun ReaderProgressHint(text: String, paper: ReaderPaper) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = paper.fg.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}
