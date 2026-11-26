package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import com.ayanami.reader.ui.AggregatedHit
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.BookCover
import com.ayanami.reader.ui.components.ScreenHeader
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun SearchScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: (() -> Unit)?,
    onOpen: (AggregatedHit) -> Unit
) {
    val t = LocalReaderTokens.current
    var keyword by remember { mutableStateOf(state.keyword) }
    // 双模式：搜小说 / 搜图片（Pixiv）
    var mode by remember { mutableStateOf(SearchMode.NOVEL) }
    // 搜小说时可按「书名 / 作者」收窄
    var field by remember { mutableStateOf(SearchField.ALL) }

    // 按字段收窄：作者匹配用 contains 在 author 上，书名同理。
    // 「全部」时保持跨源聚合后的顺序。
    val filteredHits = remember(state.hits, field, keyword) {
        when (field) {
            SearchField.ALL -> state.hits
            SearchField.TITLE -> state.hits.filter {
                it.name.contains(keyword, ignoreCase = true)
            }
            SearchField.AUTHOR -> state.hits.filter {
                it.author.contains(keyword, ignoreCase = true)
            }
        }
    }

    // 搜图片是 v0.7.0 的 Pixiv 端口，这里明确说明而不是假装能用
    if (mode == SearchMode.IMAGE) {
        Column(modifier = Modifier.fillMaxSize().background(t.background)) {
            Spacer(Modifier.height(24.dp))
            ScreenHeader(title = "搜索", subtitle = "按标题 / 标签 / 画师找图")
            PixivPendingHint()
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // onBack 为 null 表示这是底部 Tab 的一级页面，不需要返回箭头
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }
            Text("搜索", style = MaterialTheme.typography.headlineMedium,
                color = t.textPrimary, fontWeight = FontWeight.Bold)
        }

        // ---- 模式切换 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchMode.entries.forEach { m ->
                val on = mode == m
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) t.primary else t.textSecondary.copy(alpha = 0.08f))
                        .clickable { mode = m }
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        m.label,
                        fontSize = 13.sp,
                        color = if (on) Color.White else t.textPrimary,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }

        // ---- 搜小说时的字段筛选 ----
        if (mode == SearchMode.NOVEL) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SearchField.entries.forEach { f ->
                    val on = field == f
                    Text(
                        f.label,
                        fontSize = 12.sp,
                        color = if (on) t.primary else t.textSecondary.copy(alpha = 0.7f),
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { field = f }
                    )
                }
            }
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
                items(filteredHits) { hit ->
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
        Row(verticalAlignment = Alignment.Top) {
            BookCover(
                coverUrl = hit.books.first().coverUrl,
                title = hit.name,
                width = 66.dp,
                height = 90.dp
            )
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    hit.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = t.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        hit.author.takeIf { it.isNotBlank() },
                        hit.books.first().kind?.takeIf { it.isNotBlank() },
                        hit.books.first().wordCount?.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hit.books.first().intro?.takeIf { it.isNotBlank() }?.let { intro ->
                    Spacer(Modifier.height(6.dp))
                    Text(intro, style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                hit.books.first().lastChapter?.takeIf { it.isNotBlank() }?.let { lc ->
                    Spacer(Modifier.height(5.dp))
                    Text("最新：$lc", style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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

/** 搜索模式。 */
enum class SearchMode(val label: String) {
    NOVEL("搜小说"),
    IMAGE("搜图片")
}

/** 搜小说时的字段筛选。 */
enum class SearchField(val label: String) {
    ALL("全部"),
    TITLE("书名"),
    AUTHOR("作者")
}

/**
 * 搜图片的说明页。
 *
 * 刻意不放任何假数据 —— 图床 Referer 和 Pixiv API 都还没接，
 * 放个空网格会让用户以为「搜到 0 条结果」，比明说更难懂。
 */
@Composable
private fun PixivPendingHint() {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp)
            .clip(RoundedCornerShape(t.cornerRadius))
            .background(t.surface)
            .padding(18.dp)
    ) {
        Column {
            Text(
                "搜图片还没接",
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "这一路走的是 Pixiv 端口，v0.7.0 交付。要做完的事：\n" +
                    "① pximg.net 图床的 Referer 注入（不注入直接 403）\n" +
                    "② 按标签 / 画师搜（需要 PHPSESSID 或 Refresh Token）\n" +
                    "③ 双列瀑布流 + 高清原图保存",
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "现在可以先在「设置 → Pixiv」把登录信息填好。",
                style = MaterialTheme.typography.bodySmall,
                color = t.primary
            )
        }
    }
}
