package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.StatusBarSpacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.core.pixiv.PixivContentType
import com.ayanami.reader.core.pixiv.PixivRankMode
import com.ayanami.reader.ui.PixivTab
import com.ayanami.reader.ui.PixivUiState
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.adultEnabled
import com.ayanami.reader.ui.artistWorksOf
import com.ayanami.reader.ui.closePixivArtist
import com.ayanami.reader.ui.components.ArtistCard
import com.ayanami.reader.ui.components.FilterPill
import com.ayanami.reader.ui.components.NovelRankCard
import com.ayanami.reader.ui.components.PixivImage
import com.ayanami.reader.ui.components.RoundAvatar
import com.ayanami.reader.ui.components.TagWallCard
import com.ayanami.reader.ui.components.formatCount
import com.ayanami.reader.ui.ensurePixivTabLoaded
import com.ayanami.reader.ui.loadMorePixiv
import com.ayanami.reader.ui.loadPixivRanking
import com.ayanami.reader.ui.loadPixivTags
import com.ayanami.reader.ui.openPixivArtist
import com.ayanami.reader.ui.openPixivNovel
import com.ayanami.reader.ui.openPixivViewer
import com.ayanami.reader.ui.pixivSearch
import com.ayanami.reader.ui.requestOpenNovel
import com.ayanami.reader.ui.setPixivRankSafe
import com.ayanami.reader.ui.theme.CardTint
import com.ayanami.reader.ui.theme.LocalReaderTokens
import com.ayanami.reader.ui.theme.PillIdleBg
import com.ayanami.reader.ui.theme.PillSelectedBg

/**
 * Pixiv 页：排行榜 / 热门标签 / 小说 / 画师。
 *
 * ### 状态隔离
 *
 * 本页只读 `pixivState.ranking*` 系列，**不碰** `search*`。
 * 搜索页只读 `search*`。这样「搜完插画切回 Pixiv」不会互相覆盖
 * —— v0.7.0 两边共用同一份列表，是明确的 bug。
 *
 * ### 点 Tag 的行为
 *
 * Tab 墙 / 榜单里的 tag 点击 → 切到「搜索」页并带上关键词。
 * v0.7.0 是在本页原地调 `pixivSearch()`，但本页没有渲染搜索结果的地方，
 * 所以点完画面不变（看起来像没反应）。
 */
@Composable
fun PixivScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onOpenNovel: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 跳到搜索页并带上关键词（Tag 点击、搜索框提交用）。 */
    onSearchTag: (String) -> Unit,
    /** 打开画师详情（二级页）。 */
    onOpenArtist: () -> Unit
) {
    val t = LocalReaderTokens.current
    vm.settingsTick()
    val ps = vm.pixivState
    ps.adultOn = vm.adultEnabled

    LaunchedEffect(ps.tab, ps.rankMode, ps.contentType) {
        vm.ensurePixivTabLoaded()
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        StatusBarSpacer()

        // ---- 顶部：品牌 + 登录状态 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pixiv",
                style = MaterialTheme.typography.headlineMedium,
                color = t.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (vm.settings.pixivCookie.isNotBlank()) PillSelectedBg else PillIdleBg)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (vm.settings.pixivCookie.isNotBlank()) "已登录" else "免登录",
                    fontSize = 11.sp,
                    color = if (vm.settings.pixivCookie.isNotBlank()) t.primary else t.textSecondary
                )
            }
        }

        // ---- 子 Tab ----
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            items(PixivTab.entries.toList()) { tab ->
                FilterPill(
                    label = tab.label,
                    selected = ps.tab == tab,
                    onClick = { ps.tab = tab }
                )
            }
        }

        when (ps.tab) {
            PixivTab.RANKING -> RankingSection(vm, ps, onOpenViewer)
            PixivTab.NOVEL -> NovelSection(vm, ps, onOpenNovel)
            PixivTab.TAGS -> TagWallSection(vm, ps, onSearchTag)
            PixivTab.USERS -> ArtistSection(vm, ps, onOpenArtist)
        }
    }
}

// ---------------- 排行榜 ----------------

@Composable
private fun RankingSection(
    vm: ReaderViewModel,
    ps: PixivUiState,
    onOpenViewer: () -> Unit
) {
    val t = LocalReaderTokens.current

    Column(modifier = Modifier.fillMaxSize()) {
        // 周期胶囊（R18 项随总开关出现）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            val modes = PixivRankMode.entries.filter { ps.adultOn || !it.adult }
            items(modes) { m ->
                FilterPill(
                    label = m.label,
                    selected = ps.rankMode == m,
                    onClick = { vm.setPixivRankSafe(m, ps.contentType) }
                )
            }
        }

        // 内容类型
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PixivContentType.entries.forEach { ct ->
                val on = ps.contentType == ct
                Text(
                    ct.label,
                    fontSize = 12.sp,
                    color = if (on) t.primary else t.textSecondary.copy(alpha = 0.75f),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable {
                        if (ps.contentType != ct) vm.setPixivRankSafe(ps.rankMode, ct)
                    }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            ps.rankingLoading -> LoadingBox("正在拉 Pixiv 榜单…")
            ps.rankingError != null && ps.rankingIllusts.isEmpty() && ps.rankingNovels.isEmpty() ->
                PixivErrorBox(ps.rankingError!!) { vm.loadPixivRanking(force = true) }
            ps.contentType == PixivContentType.NOVEL ->
                NovelList(ps, vm)
            else -> IllustGrid(ps, vm, onOpenViewer)
        }
    }
}

@Composable
private fun IllustGrid(
    ps: PixivUiState,
    vm: ReaderViewModel,
    onOpenViewer: () -> Unit
) {
    val t = LocalReaderTokens.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItems(
            items = ps.rankingIllusts,
            key = { it.id + "_" + it.rank }
        ) { illust: com.ayanami.reader.core.pixiv.PixivIllust ->
            Column(
                modifier = Modifier.clickable {
                    vm.openPixivViewer(ps.rankingIllusts, ps.rankingIllusts.indexOf(illust))
                    onOpenViewer()
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PillIdleBg)
                ) {
                    PixivImage(
                        url = illust.thumb,
                        contentDescription = illust.title,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (illust.rank in 1..99) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "#${illust.rank}", color = Color.White, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (illust.pageCount > 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text("${illust.pageCount}P", color = Color.White, fontSize = 10.sp)
                        }
                    }
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
        if (ps.rankingLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = t.primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        } else if (ps.rankingHasMore && ps.rankingIllusts.isNotEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(14.dp).clickable { vm.loadMorePixiv() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("加载更多", fontSize = 12.sp, color = t.primary)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 小说榜列表。
 *
 * 首屏数据来自榜单 HTML（1 个请求），所以是**立刻可见**的。
 * 作者头像 / 精确字数由后台 [novelEnrichProgress] 逐条补，
 * 顶部显示「正在补全 3/12」，用户不会觉得卡住。
 */
@Composable
private fun NovelList(
    ps: PixivUiState,
    vm: ReaderViewModel
) {
    val t = LocalReaderTokens.current
    if (ps.rankingNovels.isEmpty() && !ps.rankingLoading) {
        PixivErrorBox(ps.rankingError ?: "这个榜单没有拿到小说。换个月榜试试。") {
            vm.loadPixivRanking(force = true)
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ps.novelEnrichProgress?.let { (done, total) ->
            Text(
                "正在补全详情 $done/$total（不影响阅读）",
                fontSize = 11.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(ps.rankingNovels, key = { it.id }) { novel ->
                NovelRankCard(
                    novel = novel,
                    onClick = {
                        vm.openPixivNovel(novel)
                        vm.requestOpenNovel()
                    }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------- 小说 Tab ----------------

@Composable
private fun NovelSection(
    vm: ReaderViewModel,
    ps: PixivUiState,
    onOpenNovel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            val modes = PixivRankMode.entries.filter { ps.adultOn || !it.adult }
            items(modes) { m ->
                FilterPill(
                    label = m.label,
                    selected = ps.rankMode == m,
                    onClick = { vm.setPixivRankSafe(m, PixivContentType.NOVEL) }
                )
            }
        }
        if (ps.rankingLoading) {
            LoadingBox("正在拉小说榜…")
        } else if (ps.rankingNovels.isEmpty()) {
            PixivErrorBox(ps.rankingError ?: "这个榜单暂时没拿到小说") {
                vm.loadPixivRanking(force = true)
            }
        } else {
            NovelList(ps, vm)
        }
    }
}

// ---------------- 标签墙 ----------------

@Composable
private fun TagWallSection(
    vm: ReaderViewModel,
    ps: PixivUiState,
    onSearchTag: (String) -> Unit
) {
    if (ps.rankingLoading && ps.rankingTags.isEmpty()) {
        LoadingBox("正在聚合热门标签…")
        return
    }
    if (ps.rankingTags.isEmpty()) {
        PixivErrorBox(ps.rankingError ?: "没拿到热门标签") { vm.loadPixivTags(force = true) }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(ps.rankingTags) { tag: String ->
                TagWallCard(
                    tag = tag,
                    coverUrl = ps.tagCovers[tag].orEmpty(),
                    translated = null,
                    // ⚠️ 必须跳去搜索页，不能只在本页调搜索 ——
                    // 本页没有渲染搜索结果的地方，点了会"看起来没反应"
                    onClick = { onSearchTag(tag) }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------- 画师 Tab ----------------

@Composable
private fun ArtistSection(
    vm: ReaderViewModel,
    ps: PixivUiState,
    onOpenArtist: () -> Unit
) {
    val t = LocalReaderTokens.current

    if (ps.searchUsers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CardTint)
                .padding(18.dp)
        ) {
            Column {
                Text(
                    "画师搜索在「搜索」页",
                    fontSize = 14.sp,
                    color = t.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pixiv 没有公开的免登录画师搜索接口，所以是按作品反查作者。\n" +
                        "去搜索页切到「Pixiv画师」子 Tab，输入画师名即可。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = t.textSecondary
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(ps.searchUsers, key = { it.id }) { user ->
            ArtistCard(
                user = user,
                onClick = {
                    vm.openPixivArtist(user)
                    onOpenArtist()
                }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------- 画师详情页（二级页）----------------

/**
 * 画师详情：代表作横幅 + 作品网格。
 *
 * 点击作品进入大图浏览器（复用同一条链路）。
 */
@Composable
fun PixivArtistScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onOpenViewer: () -> Unit
) {
    val t = LocalReaderTokens.current
    val ps = vm.pixivState
    val artist = ps.openedArtist

    if (artist == null) {
        onBack()
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, top = 34.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                vm.closePixivArtist()
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
            }
            Text(
                "画师",
                style = MaterialTheme.typography.titleLarge,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 头部：头像 + 名字 + 作品数
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundAvatar(url = artist.avatar, size = 56.dp, name = artist.name)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    artist.name.ifBlank { "画师 ${artist.id}" },
                    style = MaterialTheme.typography.titleLarge,
                    color = t.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append("ID ${artist.id}")
                        if (ps.artistWorks.isNotEmpty()) append(" · ${ps.artistWorks.size} 件作品")
                    },
                    fontSize = 12.sp,
                    color = t.textSecondary
                )
            }
        }

        when {
            ps.artistWorksLoading && ps.artistWorks.isEmpty() ->
                LoadingBox("正在拉画师作品…")
            ps.artistWorksError != null && ps.artistWorks.isEmpty() ->
                PixivErrorBox(ps.artistWorksError!!) { vm.openPixivArtist(artist) }
            ps.artistWorks.isEmpty() ->
                PixivErrorBox("这个画师没有公开作品") { vm.openPixivArtist(artist) }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(ps.artistWorks) { work ->
                    Column(
                        modifier = Modifier.clickable {
                            vm.openPixivViewer(ps.artistWorks, ps.artistWorks.indexOf(work))
                            onOpenViewer()
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(PillIdleBg)
                        ) {
                            PixivImage(
                                url = work.thumb,
                                contentDescription = work.title,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            work.title.ifBlank { "无题" },
                            fontSize = 12.sp,
                            color = t.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (work.bookmarkCount > 0) {
                            Text(
                                "♡ ${formatCount(work.bookmarkCount)}",
                                fontSize = 10.sp,
                                color = t.textSecondary
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ---------------- 公共小件 ----------------

@Composable
internal fun LoadingBox(text: String) {
    val t = LocalReaderTokens.current
    Box(modifier = Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp), color = t.primary, strokeWidth = 2.dp
            )
            Spacer(Modifier.height(12.dp))
            Text(text, fontSize = 12.sp, color = t.textSecondary)
        }
    }
}

/**
 * 错误提示框：① 说清原因 ② 给重试 ③ 不让用户以为"本来就空"。
 * 这是「严禁假数据」的另一半。
 */
@Composable
internal fun PixivErrorBox(message: String, onRetry: () -> Unit) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardTint)
            .padding(16.dp)
    ) {
        Column {
            Text(
                "没拉到数据",
                fontSize = 14.sp,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = t.textSecondary)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PillSelectedBg)
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text("重试", fontSize = 12.sp, color = t.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
