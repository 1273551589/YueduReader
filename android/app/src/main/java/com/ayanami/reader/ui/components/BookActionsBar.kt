package com.ayanami.reader.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.core.download.DownloadEngine
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * 详情页的操作条：缓存下载 + 跨源切换。
 *
 * 拆成独立组件是因为详情页已经有封面区、简介、目录列表，
 * 再往里塞进度条和胶囊会让那个文件难读。
 */
@Composable
fun DownloadBar(
    cached: Int,
    total: Int,
    task: DownloadEngine.Task?,
    onDownloadAll: () -> Unit,
    onDownloadNext: () -> Unit,
    onPause: () -> Unit,
    onExport: () -> Unit
) {
    val t = LocalReaderTokens.current
    val running = task?.state == DownloadEngine.Task.State.RUNNING

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "离线缓存",
                    style = MaterialTheme.typography.titleMedium,
                    color = t.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        task != null && running ->
                            "正在下载 ${task.completed}/${task.total}"
                        task?.state == DownloadEngine.Task.State.PAUSED ->
                            "已暂停 · 已缓存 $cached/$total"
                        task?.state == DownloadEngine.Task.State.DONE ->
                            "已全部缓存 · $cached/$total"
                        task?.state == DownloadEngine.Task.State.FAILED ->
                            "部分失败（${task.failedCount} 章）· 已缓存 $cached/$total"
                        else -> "已缓存 $cached/$total 章"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary
                )
            }
            if (running) {
                ActionChip("暂停", t.accent, onPause)
            } else {
                ActionChip("全本", t.primary, onDownloadAll)
                Spacer(Modifier.width(7.dp))
                ActionChip("后续50", t.primary, onDownloadNext)
            }
        }

        // 进度条
        if (task != null && (running || task.state == DownloadEngine.Task.State.PAUSED)) {
            Spacer(Modifier.height(9.dp))
            val p = task.progress.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(t.textSecondary.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(t.primary)
                )
            }
        }

        if (cached > 0) {
            Spacer(Modifier.height(10.dp))
            ActionChip("导出 TXT", t.textSecondary, onExport)
        }
    }
}

/** 跨源切换条。只在当前书在别的源也有时显示。 */
@Composable
fun SourceSwitchBar(alternates: List<Pair<String, String>>, onSwitch: (String) -> Unit) {
    if (alternates.isEmpty()) return
    val t = LocalReaderTokens.current
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            "其他书源也有这本",
            style = MaterialTheme.typography.labelSmall,
            color = t.textSecondary
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            alternates.take(4).forEach { (url, name) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(t.textSecondary.copy(alpha = 0.09f))
                        .clickable { onSwitch(url) }
                        .padding(horizontal = 13.dp, vertical = 6.dp)
                ) {
                    Text(
                        name,
                        fontSize = 12.sp,
                        color = t.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.11f))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
