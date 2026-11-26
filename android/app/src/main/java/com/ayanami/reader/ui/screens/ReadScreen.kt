package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.downloadTask
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun ReadScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit
) {
    val t = LocalReaderTokens.current
    val scroll = rememberScrollState()

    // 读设置（settingsTick 让改动立刻生效）
    vm.settingsTick()
    val cfg = vm.settings
    val paper = readerPaper(cfg.readerTheme)
    // 正文行高 = 字号 × 倍数。Compose 的 lineHeight 是绝对值，必须自己乘
    val bodySize = cfg.fontSize.sp
    val bodyLine = (cfg.fontSize * cfg.lineHeightPercent / 100f).sp

    Column(modifier = Modifier.fillMaxSize().background(paper.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = paper.fg)
            }
            Text(
                state.chapter?.title ?: state.openedBook?.name ?: "阅读",
                style = MaterialTheme.typography.titleMedium,
                color = paper.fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val idx = state.chapterIndex
            val toc = state.toc
            IconButton(
                enabled = idx > 0,
                onClick = { if (idx > 0) vm.loadChapter(toc[idx - 1], idx - 1) }
            ) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一章",
                    tint = if (idx > 0) paper.fg else paper.fg.copy(alpha = 0.28f))
            }
            IconButton(
                enabled = idx in 0 until toc.size - 1,
                onClick = { if (idx in 0 until toc.size - 1) vm.loadChapter(toc[idx + 1], idx + 1) }
            ) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一章",
                    tint = if (idx in 0 until toc.size - 1) paper.fg else paper.fg.copy(alpha = 0.28f))
            }
        }

        if (state.loadingChapter) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), color = paper.accent, strokeWidth = 2.dp)
            }
            return@Column
        }

        val chapter = state.chapter
        if (chapter == null) {
            EmptyHint("选择一章开始读")
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                color = paper.fg,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(20.dp))
            Text(
                chapter.text,
                fontSize = bodySize,
                lineHeight = bodyLine,
                color = paper.fg
            )
            if (chapter.images.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("本章含 ${chapter.images.size} 张图（首版仅列出）",
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                chapter.images.forEachIndexed { i, url ->
                    Text("$i. $url", style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(24.dp))
            // 章节位置 + 缓存状态
            val downloading = vm.downloadTask(state.openedBook?.bookUrl ?: "") != null
            Text(
                buildString {
                    append("${state.chapterIndex + 1} / ${state.toc.size}")
                    if (downloading) append(" · 已在离线缓存")
                },
                style = MaterialTheme.typography.labelSmall,
                color = paper.fg.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(60.dp))
        }
    }
}


/**
 * 阅读配色。
 *
 * 不能直接复用 App 主题色 —— 阅读页需要「近乎纯色的纸」，而 App 界面
 * 用的是卡片式浅底。护眼模式尤其不能沿用主界面，否则眼睛会累。
 */
private data class ReaderPaper(
    val bg: androidx.compose.ui.graphics.Color,
    val fg: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color
)

private fun readerPaper(theme: com.ayanami.reader.data.Settings.ReaderTheme): ReaderPaper =
    when (theme) {
        // 日间：接近纯白，正文用近黑（不是纯 #000，那样对比过强会刺眼）
        com.ayanami.reader.data.Settings.ReaderTheme.DAY -> ReaderPaper(
            bg = androidx.compose.ui.graphics.Color(0xFFFDFCFA),
            fg = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
            accent = androidx.compose.ui.graphics.Color(0xFF3B6FE0)
        )
        // 护眼：米黄纸 + 深棕字，模拟纸质书
        com.ayanami.reader.data.Settings.ReaderTheme.EYE_CARE -> ReaderPaper(
            bg = androidx.compose.ui.graphics.Color(0xFFF3E9D2),
            fg = androidx.compose.ui.graphics.Color(0xFF3D3527),
            accent = androidx.compose.ui.graphics.Color(0xFF8A6A3A)
        )
        // 夜间：深灰而非纯黑（纯黑配白字在 OLED 上会有拖影）
        com.ayanami.reader.data.Settings.ReaderTheme.NIGHT -> ReaderPaper(
            bg = androidx.compose.ui.graphics.Color(0xFF16181C),
            fg = androidx.compose.ui.graphics.Color(0xFFC9CBD0),
            accent = androidx.compose.ui.graphics.Color(0xFF6D9CFF)
        )
    }
