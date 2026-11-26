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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.data.Settings
import com.ayanami.reader.ui.BookCatalog
import com.ayanami.reader.ui.BookstoreState
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.adultEnabled
import com.ayanami.reader.ui.loadBookstore
import com.ayanami.reader.ui.loadMoreBookstore
import com.ayanami.reader.ui.readableSourceCount
import com.ayanami.reader.ui.refreshBookstoreWithCheck
import com.ayanami.reader.ui.components.BookCover
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.ScreenHeader
import com.ayanami.reader.ui.theme.LocalReaderTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 书城聚合页 —— 把**所有通过体检的书源**的发现页内容聚到一起。
 *
 * ### 三段筛选 + 排序
 *
 * - 第 1 排主分类（R18 打开时追加成人向细分）
 * - 第 2 排内容分级（未开 R18 时只有「全部」「全年龄」）
 * - 第 3 排书源胶囊
 * - 排序栏：综合 / 近期更新 / 字数倒序 / 字数正序
 *
 * ### 渐进式加载
 *
 * 首屏只拉每个源的第 1 页；滚到接近底部才拉下一页。
 * 一次性全量爬会把流量烧光、把站打挂、还让首屏等半分钟。
 */
@Composable
fun BookstoreScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onOpen: (BookstoreEngine.StoreBook) -> Unit
) {
    val t = LocalReaderTokens.current
    val bs = vm.bookstoreState
    // 读设置计数，让 R18 开关变化能触发重组
    vm.settingsTick()
    val adultOn = vm.adultEnabled

    LaunchedEffect(Unit) { vm.loadBookstore() }

    val listState = rememberLazyListState()
    val visible = remember(bs.allBooks, bs.genre, bs.rating, bs.sourceFilter, bs.sort, adultOn) {
        bs.visible(vm.bookstoreEngine, adultOn)
    }

    // 滚到倒数第 6 个时预加载下一页 —— 用户不会看到加载停顿
    LaunchedEffect(listState, bs.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it > 0 && it >= visible.size - 6 }
            .collect { vm.loadMoreBookstore() }
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        StatusBarSpacer()
        ScreenHeader(
            title = "书城",
            subtitle = if (bs.sources.isEmpty()) "没有可逛的书源" else
                "${bs.sources.size} 个可读源 · ${visible.size} 本"
        )

        // ---- 第 1 排：主分类 ----
        FilterRow(
            items = bs.genres(adultOn),
            selected = bs.genre,
            onSelect = { bs.genre = it }
        )

        // ---- R18 细分（仅开关打开时追加第二行）----
        val adultCats = bs.adultGenres(adultOn)
        if (adultCats.isNotEmpty()) {
            FilterRow(
                items = listOf("ALL" to "不限") + adultCats,
                selected = bs.adultSubGenre ?: "ALL",
                onSelect = { bs.adultSubGenre = if (it == "ALL") null else it },
                dim = true
            )
        }

        // ---- 第 2 排：内容分级 ----
        FilterRow(
            items = buildList {
                add("ALL" to "全部")
                add("GENERAL" to "全年龄")
                if (adultOn) add("ADULT" to "成人向")
            },
            selected = bs.rating,
            onSelect = { bs.rating = it }
        )

        // ---- 第 3 排：书源 ----
        if (bs.sources.size > 1) {
            FilterRow(
                items = listOf("ALL" to "全部书源") + bs.sources.map {
                    it.source.bookSourceUrl to it.displayName
                },
                selected = bs.sourceFilter,
                onSelect = { bs.sourceFilter = it },
                dim = true
            )
        }

        // ---- 排序栏 ----
        SortBar(current = bs.sort, onSelect = { bs.sort = it })

        Spacer(Modifier.height(6.dp))

        when {
            bs.loading -> LoadingBlock("正在聚合各源书单…")
            bs.sources.isEmpty() -> EmptyBookstore(
                readableCount = vm.readableSourceCount(),
                onRefresh = { vm.refreshBookstoreWithCheck() }
            )
            visible.isEmpty() -> EmptyHint("这个筛选下没有书，换个分类看看")
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { book ->
                    StoreBookRow(book = book, onClick = { onOpen(book) })
                }
                if (bs.loadingMore) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = t.primary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                } else if (!bs.hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("没有更多了", style = MaterialTheme.typography.bodySmall,
                                color = t.textSecondary)
                        }
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }

        // 错误提示（非阻塞，放底部）
        if (bs.errors.isNotEmpty() && !bs.loading) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                Text(
                    "${bs.errors.size} 个源未返回内容",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textSecondary.copy(alpha = 0.65f)
                )
            }
        }
    }
}

/** 一行可横向滚动的筛选胶囊。 */
@Composable
private fun FilterRow(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    dim: Boolean = false
) {
    val t = LocalReaderTokens.current
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        items(items, key = { it.first }) { (key, label) ->
            val on = key == selected
            val bg = when {
                on -> t.primary
                dim -> t.textSecondary.copy(alpha = 0.08f)
                else -> t.surface
            }
            val fg = when {
                on -> Color.White
                dim -> t.textSecondary
                else -> t.textPrimary
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = fg,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/** 排序栏：靠右的小字按钮组。 */
@Composable
private fun SortBar(current: String, onSelect: (String) -> Unit) {
    val t = LocalReaderTokens.current
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        items(Settings.SortMode.entries.toList(), key = { it.name }) { mode ->
            val on = mode.name == current
            Text(
                mode.label,
                fontSize = 12.sp,
                color = if (on) t.primary else t.textSecondary.copy(alpha = 0.75f),
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { onSelect(mode.name) }
            )
        }
    }
}

/** 一本聚合书目的卡片行。 */
@Composable
private fun StoreBookRow(book: BookstoreEngine.StoreBook, onClick: () -> Unit) {
    val t = LocalReaderTokens.current
    val b = book.book
    val cls = book.classification

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(t.cornerRadius))
            .background(t.surface)
            .clickable { onClick() }
            .padding(13.dp),
        verticalAlignment = Alignment.Top
    ) {
        BookCover(
            coverUrl = b.coverUrl,
            title = b.name,
            width = 72.dp,
            height = 99.dp
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                b.name,
                style = MaterialTheme.typography.titleMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    b.author.takeIf { it.isNotBlank() },
                    b.wordCount?.takeIf { it.isNotBlank() }?.let { "${it}字" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            b.intro?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(5.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 分类 Tag（本地算出来的，不是书源标的）
                TagChip(cls.genre.label, t.primary)
                cls.tags.take(1).forEach { TagChip(it, t.textSecondary) }
                if (cls.isAdult) TagChip("成人向", Color(0xFFB0468C))
            }
        }
    }
}

@Composable
private fun TagChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun LoadingBlock(text: String) {
    val t = LocalReaderTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = t.primary,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.height(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
    }
}

/** 没有可逛的书源时的空状态 —— 直接给「一键体检并加载」。 */
@Composable
private fun EmptyBookstore(readableCount: Int, onRefresh: () -> Unit) {
    val t = LocalReaderTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (readableCount == 0) "还没有通过体检的书源" else "书源还没返回内容",
            style = MaterialTheme.typography.titleMedium,
            color = t.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (readableCount == 0)
                "书城只聚合通过四段校验的书源。\n去「设置 → 书源」导入并体检，或者点下面一键开始。"
            else "可能是网络问题，点下面重新体检并加载。",
            style = MaterialTheme.typography.bodySmall,
            color = t.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(t.primary)
                .clickable { onRefresh() }
                .padding(horizontal = 22.dp, vertical = 10.dp)
        ) {
            Text("体检并加载书城", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Medium)
        }
    }
}
