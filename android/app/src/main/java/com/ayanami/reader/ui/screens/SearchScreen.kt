package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.ayanami.reader.ui.AggregatedHit
import com.ayanami.reader.ui.PixivSearchMode
import com.ayanami.reader.ui.SearchTab
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.ArtistCard
import com.ayanami.reader.ui.components.BookCover
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.FilterPill
import com.ayanami.reader.ui.components.NovelRankCard
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.components.PixivImage
import com.ayanami.reader.ui.components.formatCount
import com.ayanami.reader.ui.components.SectionHeaderRow
import com.ayanami.reader.ui.loadPixivTags
import com.ayanami.reader.ui.openPixivNovel
import com.ayanami.reader.ui.openPixivViewer
import com.ayanami.reader.ui.clearPixivSearch
import com.ayanami.reader.ui.openPixivArtist
import com.ayanami.reader.ui.pixivSearch
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * 搜索页 —— 四个子 Tab。
 *
 * ### v0.6.0 的致命 bug
 *
 * `if (mode == SearchMode.IMAGE) { ...; return }` 放在了最外层，
 * 那个分支里**没有模式切换胶囊**，于是切到「搜图片」之后就再也
 * 切不回「搜小说」—— 用户只能杀掉 App。
 *
 * 这里的结构严格是：**标题栏 + 子 Tab 胶囊永远在最外层**，
 * 内容区才做分支。任何 `return` 之后都不能再有导航控件。
 *
 * ### 四个子 Tab 的分工
 *
 * | Tab | 数据源 | 无关键词时 |
 * |---|---|---|
 * | 书源小说 | 本地导入的书源 | 提示输入 |
 * | Pixiv插画·漫画 | `/ajax/search/top` | 2 列 Tag 推荐墙 |
 * | Pixiv小说 | 同上（novel 桶） | 提示输入 |
 * | Pixiv画师 | 作品反查作者 | 提示输入 |
 */
@Composable
fun SearchScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: (() -> Unit)?,
    onOpen: (AggregatedHit) -> Unit,
    onOpenViewer: () -> Unit = {},
    onOpenNovel: () -> Unit = {},
    /** 打开画师详情（二级页）。 */
    onOpenArtist: () -> Unit = {}
) {
    val t = LocalReaderTokens.current
    val ps = vm.pixivState

    // Tab 选择放到 ViewModel：外部（例如 Pixiv 页点 Tag）要能指定
    // "打开搜索页的哪个 Tab"，局部 remember 做不到
    val mode = vm.searchTab
    val setMode: (SearchTab) -> Unit = { t ->
        vm.searchTab = t
        if (t == SearchTab.PIXIV_ILLUST && ps.rankingTags.isEmpty()) vm.loadPixivTags()
    }
    // 关键词：优先用外部注入的（Pixiv 点 Tag 时带过来的），
    // 其次才是书源搜索的上次关键词
    var keyword by remember {
        mutableStateOf(
            vm.searchKeywordBridge.ifBlank { state.keyword }
        )
    }

    // ⚠️ 必须跟随 bridge 变化重新赋值。
    // `remember` 只在首次组合时求值 —— 用户从 Pixiv 点第二个 Tag 时，
    // 关键词会一直停在上一个，看起来像"点了没反应"。
    LaunchedEffect(vm.searchKeywordBridge) {
        val bridge = vm.searchKeywordBridge
        if (bridge.isNotBlank() && bridge != keyword) keyword = bridge
    }
    var field by remember { mutableStateOf(SearchField.ALL) }

    // 书源搜索结果按字段收窄
    val filteredHits = remember(state.hits, field, keyword) {
        when (field) {
            SearchField.ALL -> state.hits
            SearchField.TITLE -> state.hits.filter { it.name.contains(keyword, ignoreCase = true) }
            SearchField.AUTHOR -> state.hits.filter { it.author.contains(keyword, ignoreCase = true) }
        }
    }

    // 切到 Pixiv 子 Tab 且没有关键词时，懒加载 Tag 墙
    val pixivMode = mode.toPixivMode()

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        // ================= 标题栏（永远在最外层）=================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }
            Text(
                "搜索",
                style = MaterialTheme.typography.headlineMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        // ================= 子 Tab（永远在最外层）=================
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            items(SearchTab.entries.toList()) { tab ->
                FilterPill(
                    label = tab.label,
                    selected = mode == tab,
                    onClick = { setMode(tab) }
                )
            }
        }

        // ================= 搜索框 =================
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = {
                Text(
                    if (mode == SearchTab.SOURCE) "书名或作者" else "标签、标题或画师名",
                    color = t.textSecondary
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = t.textSecondary)
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 清空按钮：清掉关键词 + 回到 Tag 推荐墙。
                    // 只在有内容时出现 —— 空输入框挂个 × 是噪音。
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = {
                            keyword = ""
                            if (mode == SearchTab.SOURCE) {
                                vm.search("")   // 书源侧 blank 直接 return，UI 靠 keyword 判断显示
                            } else {
                                vm.clearPixivSearch()
                            }
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清空",
                                tint = t.textSecondary
                            )
                        }
                    }
                    IconButton(onClick = { doSearch(vm, mode, keyword) }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜", tint = t.primary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = t.primary.copy(alpha = 0.45f),
                unfocusedBorderColor = t.textSecondary.copy(alpha = 0.18f)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(vm, mode, keyword) })
        )

        // ================= 书源模式的字段筛选 =================
        if (mode == SearchTab.SOURCE) {
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

        // ================= 内容区 =================
        when (mode) {
            SearchTab.SOURCE -> SourceResultSection(
                state = state,
                hits = filteredHits,
                keyword = keyword,
                onOpen = onOpen
            )

            SearchTab.PIXIV_ILLUST -> PixivIllustSection(
                vm = vm,
                ps = ps,
                keyword = keyword,
                onOpenViewer = onOpenViewer,
                onPickTag = { tag ->
                    keyword = tag
                    vm.pixivSearch(tag, PixivSearchMode.ILLUST)
                }
            )

            SearchTab.PIXIV_NOVEL -> PixivNovelSection(
                vm = vm,
                ps = ps,
                onOpenNovel = onOpenNovel
            )

            SearchTab.PIXIV_USER -> PixivUserSection(vm, ps, onOpenArtist)
        }
    }
}

/** 触发搜索（按当前 Tab 分流）。 */
private fun doSearch(vm: ReaderViewModel, mode: SearchTab, keyword: String) {
    if (keyword.isBlank()) return
    when (mode) {
        SearchTab.SOURCE -> vm.search(keyword)
        SearchTab.PIXIV_ILLUST -> vm.pixivSearch(keyword, PixivSearchMode.ILLUST)
        SearchTab.PIXIV_NOVEL -> vm.pixivSearch(keyword, PixivSearchMode.NOVEL)
        SearchTab.PIXIV_USER -> vm.pixivSearch(keyword, PixivSearchMode.USER)
    }
}

private fun SearchTab.toPixivMode(): PixivSearchMode? = when (this) {
    SearchTab.SOURCE -> null
    SearchTab.PIXIV_ILLUST -> PixivSearchMode.ILLUST
    SearchTab.PIXIV_NOVEL -> PixivSearchMode.NOVEL
    SearchTab.PIXIV_USER -> PixivSearchMode.USER
}

// ---------------- 书源结果 ----------------

@Composable
private fun SourceResultSection(
    state: ReaderUiState,
    hits: List<AggregatedHit>,
    keyword: String,
    onOpen: (AggregatedHit) -> Unit
) {
    val t = LocalReaderTokens.current

    if (state.searching) {
        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp), color = t.primary, strokeWidth = 2.dp
            )
        }
        return
    }

    if (hits.isNotEmpty()) {
        Text(
            buildString {
                append("共 ${state.hits.size} 本 · ${state.searchSourceCount} 个源")
                if (state.searchSkippedCount > 0) append("（跳过 ${state.searchSkippedCount} 个坏源）")
                if (state.searchAdultFilteredCount > 0) {
                    append(" · 已过滤 ${state.searchAdultFilteredCount} 条成人向")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = t.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(hits) { hit -> HitRow(hit, onOpen) }
            if (state.searchErrors.isNotEmpty()) {
                item {
                    Column(Modifier.padding(22.dp)) {
                        Text(
                            "未返回结果的源",
                            style = MaterialTheme.typography.bodySmall,
                            color = t.textSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                        state.searchErrors.forEach { (name, err) ->
                            Text(
                                "· $name：$err",
                                style = MaterialTheme.typography.bodySmall,
                                color = t.textSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
        return
    }

    if (state.keyword.isNotEmpty()) EmptyHint("没有找到结果")
    else EmptyHint("输入关键词，会在所有启用的书源里搜")
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
                    Text(
                        intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                hit.books.first().lastChapter?.takeIf { it.isNotBlank() }?.let { lc ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "最新：$lc",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.textSecondary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

// ---------------- Pixiv 插画 ----------------

@Composable
private fun PixivIllustSection(
    vm: ReaderViewModel,
    ps: com.ayanami.reader.ui.PixivUiState,
    keyword: String,
    onOpenViewer: () -> Unit,
    onPickTag: (String) -> Unit
) {
    val t = LocalReaderTokens.current

    if (ps.searchLoading) {
        LoadingBox("正在搜 Pixiv…")
        return
    }
    if (ps.searchError != null && ps.searchIllusts.isEmpty()) {
        PixivErrorBox(ps.searchError!!) { vm.pixivSearch(keyword, PixivSearchMode.ILLUST) }
        return
    }

    // 无关键词 → 2 列 Tag 推荐墙
    if (keyword.isBlank() && ps.searchIllusts.isEmpty()) {
        if (ps.rankingTags.isEmpty()) {
            EmptyHint("输入标签，或先看看下面的热门 Tag")
            vm.loadPixivTags()
            return
        }
        SectionHeaderRow(title = "热门标签", leading = {
            Text("🔥", fontSize = 14.sp)
        })
        TagWallGrid(vm, ps, onPickTag)
        return
    }

    if (ps.searchIllusts.isEmpty()) {
        EmptyHint("没搜到插画")
        return
    }

    // 相关 tag（点一下直接换关键词）
    if (ps.searchTags.isNotEmpty()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            items(ps.searchTags.take(10)) { tag ->
                FilterPill(
                    label = "#$tag",
                    selected = false,
                    onClick = { onPickTag(tag) }
                )
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(ps.searchIllusts, key = { _, it -> it.id }) { index, illust ->
            Column(modifier = Modifier.clickable {
                vm.openPixivViewer(ps.searchIllusts, index)
                onOpenViewer()
            }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    PixivImage(
                        url = illust.thumb,
                        contentDescription = illust.title,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    illust.title.ifBlank { "无题" },
                    fontSize = 12.sp,
                    color = t.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        illust.authorName.ifBlank { "—" },
                        fontSize = 10.sp,
                        color = t.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (illust.bookmarkCount > 0) {
                        Text(
                            "♡ ${formatCount(illust.bookmarkCount)}",
                            fontSize = 10.sp,
                            color = t.textSecondary
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

/**
 * 2 列 Tag 墙。
 *
 * ⚠️ 封面必须读 `ps.tagCovers`，**不能**再从 `ps.illusts` 里推断。
 *
 * v0.7.0 第一版就是读 `ps.illusts`（按「作品 tag 包含这个词」去匹配），
 * 结果第一次进页面时 `illusts` 是空的 → 16 个格子全是灰色空块。
 * 现在由 [loadPixivTags] 对每个 tag 各搜一次，把首图存进 `tagCovers`，
 * 封面和点进去的结果保证是同一个 tag。
 */
@Composable
private fun TagWallGrid(
    vm: ReaderViewModel,
    ps: com.ayanami.reader.ui.PixivUiState,
    onPickTag: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 还在补封面时给个进度，避免用户以为图裂了
        ps.tagProgress?.let { (done, total) ->
            Text(
                "正在为标签取封面 $done/$total…",
                fontSize = 11.sp,
                color = LocalReaderTokens.current.textSecondary,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ⚠️ 必须走 grid 的 items 别名：LazyColumn 也有 items，同时 import 会歧义
            gridItems(ps.rankingTags) { tag: String ->
                com.ayanami.reader.ui.components.TagWallCard(
                    tag = tag,
                    coverUrl = ps.tagCovers[tag].orEmpty(),
                    translated = null,
                    onClick = { onPickTag(tag) }
                )
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

// ---------------- Pixiv 小说 ----------------

@Composable
private fun PixivNovelSection(
    vm: ReaderViewModel,
    ps: com.ayanami.reader.ui.PixivUiState,
    onOpenNovel: () -> Unit
) {
    if (ps.searchLoading) {
        LoadingBox("正在搜 Pixiv 小说…")
        return
    }
    if (ps.searchError != null && ps.searchNovels.isEmpty()) {
        PixivErrorBox(ps.searchError!!) { vm.pixivSearch(ps.keyword, PixivSearchMode.NOVEL) }
        return
    }
    if (ps.searchNovels.isEmpty()) {
        EmptyHint("输入关键词搜 Pixiv 小说")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(ps.searchNovels, key = { it.id }) { novel ->
            NovelRankCard(novel = novel, onClick = {
                vm.openPixivNovel(novel)
                onOpenNovel()
            })
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

// ---------------- Pixiv 画师 ----------------

@Composable
private fun PixivUserSection(
    vm: ReaderViewModel,
    ps: com.ayanami.reader.ui.PixivUiState,
    onOpenArtist: () -> Unit
) {
    if (ps.searchLoading) {
        LoadingBox("正在找画师…")
        return
    }
    if (ps.searchError != null && ps.searchUsers.isEmpty()) {
        PixivErrorBox(ps.searchError!!) { vm.pixivSearch(ps.keyword, PixivSearchMode.USER) }
        return
    }
    if (ps.searchUsers.isEmpty()) {
        EmptyHint("输入画师名，会按作品反查作者")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(ps.searchUsers, key = { it.id }) { user ->
            ArtistCard(user = user, onClick = {
                vm.openPixivArtist(user)
                onOpenArtist()
            })
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

// ---------------- 枚举 ----------------

/** 搜索页的四个子 Tab。 */
// SearchTab 已移到 ui/SearchTab.kt —— 需要被 ViewModel 和 ReaderApp 引用

/** 书源搜索的字段筛选。 */
enum class SearchField(val label: String) {
    ALL("全部"),
    TITLE("书名"),
    AUTHOR("作者")
}
