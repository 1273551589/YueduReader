package com.ayanami.reader.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import com.ayanami.reader.ui.AggregatedHit
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun SearchScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit,
    onOpen: (AggregatedHit) -> Unit
) {
    val t = LocalReaderTokens.current
    var keyword by remember { mutableStateOf(state.keyword) }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 20.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
            }
            Text("搜索", style = MaterialTheme.typography.headlineMedium,
                color = t.textPrimary, fontWeight = FontWeight.Bold)
        }

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            placeholder = { Text("书名或作者", color = t.textSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(t.cornerRadius),
            trailingIcon = {
                IconButton(onClick = { vm.search(keyword) }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜", tint = t.primary)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(keyword) })
        )

        if (state.searching) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), color = t.primary, strokeWidth = 2.dp)
            }
        }

        if (state.hits.isNotEmpty()) {
            Text(
                "共 ${state.hits.size} 本 · 来自 ${state.sources.count { it.bookSourceEnabled }} 个源",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits) { hit ->
                    HitRow(hit, onOpen)
                }
                if (state.searchErrors.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(22.dp)) {
                            Text("未返回结果的源", style = MaterialTheme.typography.bodySmall,
                                color = t.textSecondary)
                            Spacer(Modifier.height(6.dp))
                            state.searchErrors.forEach { (name, err) ->
                                Text("· $name：$err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = t.textSecondary.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        } else if (!state.searching) {
            if (state.keyword.isNotEmpty()) {
                EmptyHint("没有找到结果")
            } else {
                EmptyHint("输入关键词，会在所有启用的书源里搜")
            }
        }
    }
}

@Composable
private fun HitRow(hit: AggregatedHit, onOpen: (AggregatedHit) -> Unit) {
    val t = LocalReaderTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .clip(RoundedCornerShape(t.cornerRadius))
            .background(t.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.name,
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = true)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                hit.author.takeIf { it.isNotBlank() },
                hit.books.first().kind?.takeIf { it.isNotBlank() },
                hit.books.first().lastChapter?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = t.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        hit.books.first().intro?.takeIf { it.isNotBlank() }?.let { intro ->
            Spacer(Modifier.height(6.dp))
            Text(intro, style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            hit.books.forEachIndexed { idx, b ->
                Pill(
                    text = b.originName,
                    color = if (idx == 0) t.primary else t.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50f))
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(t.primary.copy(alpha = 0.10f))
                    .clickable { onOpen(hit) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "打开",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
