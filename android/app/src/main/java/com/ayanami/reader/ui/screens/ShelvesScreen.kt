package com.ayanami.reader.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.QuietCard
import com.ayanami.reader.ui.components.ScreenHeader
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun ShelvesScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onMods: () -> Unit
) {
    val t = LocalReaderTokens.current
    val enabled = state.sources.count { it.bookSourceEnabled }
    val readable = state.checks.values.count { it.status == SourceChecker.UiStatus.READABLE }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Spacer(Modifier.height(28.dp))
        ScreenHeader(
            title = "书架",
            subtitle = if (state.sources.isEmpty()) "还没有书源，先导入一个"
            else "$enabled 个书源启用 · $readable 个抽样可读"
        )

        // 三个入口
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            EntryCard(
                icon = Icons.Filled.Search,
                title = "搜索",
                desc = "跨源找书",
                modifier = Modifier.weight(1f),
                onClick = onSearch
            )
            EntryCard(
                icon = Icons.Filled.Menu,
                title = "书源",
                desc = "${state.sources.size} 条",
                modifier = Modifier.weight(1f),
                onClick = onSources
            )
            EntryCard(
                icon = Icons.Filled.Build,
                title = "MOD",
                desc = "${vm.mods.mods.size} 个",
                modifier = Modifier.weight(1f),
                onClick = onMods
            )
        }

        Spacer(Modifier.height(20.dp))

        if (state.sources.isEmpty()) {
            EmptyHint("点「书源」导入 Legado 格式的 JSON，\n或者装一个带 sources 的 MOD")
        } else {
            Text(
                "最近阅读",
                style = MaterialTheme.typography.titleMedium,
                color = t.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            EmptyHint("阅读进度已在本地记录；书架 UI 是后续项")
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t = LocalReaderTokens.current
    QuietCard(modifier = modifier, onClick = onClick) {
        Column {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(t.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = t.primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = t.textPrimary,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        }
    }
}
