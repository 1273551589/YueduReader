package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.ayanami.reader.data.Settings
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.downloadTask
import com.ayanami.reader.ui.isPixivNovel
import com.ayanami.reader.ui.loadPixivChapter
import com.ayanami.reader.ui.loadRecentReading
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.reader.ChapterPaginator
import com.ayanami.reader.ui.reader.ReaderQuickPanel
import com.ayanami.reader.ui.reader.readerPaper
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 阅读页。
 *
 * ### 两种模式共用一个屏幕
 *
 * - **滚动**（默认）：`verticalScroll`，适合通读长章
 * - **翻页**：`HorizontalPager` + [ChapterPaginator] 精确分页
 *
 * 分页是**按真实视口测量**的，不是按字数估 —— 详见 [ChapterPaginator] 的注释。
 * 所以字号、行距、纸色、翻页方式一改，页数会立刻重算，
 * 并且尽量把用户留在同一段文字上（用字符下标做锚点）。
 *
 * ### 点击分区
 *
 * 左 1/3 → 上一页（翻页模式）/ 上翻一屏（滚动模式）
 * 中 1/3 → 呼出底部快捷面板
 * 右 1/3 → 下一页
 */
@OptIn(FlowPreview::class)
@Composable
fun ReadScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit,
    /**
     * Pixiv 小说切章回调。
     *
     * Pixiv 的正文是一次请求拿回全文、本地按 `[chapter:]` 切的，
     * 所以切章不能走 `loadChapter`（那会去连书源）。传 null 时
     * 保持原行为（书源书）。
     */
    onPixivChapter: ((Int) -> Unit)? = null
) {
    val isPixiv = state.isPixivNovel()
    // 统一的上/下一章动作：Pixiv 走内存，书源走网络
    val goChapter: (Int) -> Unit = { idx ->
        if (isPixiv && onPixivChapter != null) onPixivChapter(idx)
        else state.toc.getOrNull(idx)?.let { vm.loadChapter(it, idx) }
    }
    // 读设置计数，让改动立刻生效
    vm.settingsTick()
    val cfg = vm.settings
    val paper = readerPaper(cfg.readerTheme)
    val ctx = LocalContext.current

    // 正文行高 = 字号 × 倍数。Compose 的 lineHeight 是绝对值，必须自己乘
    val bodySize = cfg.fontSize.sp
    val bodyLine = (cfg.fontSize * cfg.lineHeightPercent / 100f).sp

    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val chapter = state.chapter
    val pageMode = cfg.pageMode == Settings.PageMode.PAGE

    // 当前章内锚点（字符下标）—— 切换模式时用它保持位置
    var anchorChar by remember { mutableIntStateOf(0) }

    // ---------------- 精准进度恢复 ----------------
    // 打开一章时，如果设置里允许恢复，就把锚点设回上次的字符位置。
    // 只在 chapter 真正变化时跑，不能每帧都跑（会覆盖用户的翻页）。
    LaunchedEffect(chapter?.url, cfg.restoreProgress) {
        val book = state.openedBook ?: return@LaunchedEffect
        val saved = vm.repo.loadProgressOf(book.bookUrl) ?: return@LaunchedEffect
        if (!cfg.restoreProgress) {
            anchorChar = 0
            return@LaunchedEffect
        }
        // 章节对得上才恢复页内位置（否则说明是用户主动跳章）
        if (saved.chapterIndex == state.chapterIndex) {
            anchorChar = saved.scrollOffset.coerceAtLeast(0)
        } else {
            anchorChar = 0
        }
    }

    // ---------------- 保存进度 ----------------
    // ⚠️ 不能把 `scroll.value` 直接当 LaunchedEffect 的 key ——
    // 滚动时它每帧都变，那等于每帧写一次 JSON 文件（实测卡到掉帧）。
    // 用 snapshotFlow + debounce，手指停下来 400ms 才写一次。
    LaunchedEffect(chapter?.url, pageMode, anchorChar) {
        val book = state.openedBook ?: return@LaunchedEffect
        if (chapter == null) return@LaunchedEffect
        val save: (Int) -> Unit = { anchor ->
            vm.repo.saveProgress(
                bookUrl = book.bookUrl,
                chapterIndex = state.chapterIndex,
                chapterTitle = chapter.title,
                bookName = book.name,
                pageIndex = if (pageMode) -1 else -1,
                scrollOffset = if (pageMode) anchor else scroll.value
            )
            vm.loadRecentReading()
        }
        if (pageMode) {
            save(anchorChar)
        } else {
            snapshotFlow { scroll.value }
                .debounce(400)
                .collect { save(0) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(paper.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------- 顶栏 ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = paper.fg)
                }
                Text(
                    chapter?.title ?: state.openedBook?.name ?: "阅读",
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
                    onClick = { if (idx > 0) goChapter(idx - 1) }
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章",
                        tint = if (idx > 0) paper.fg else paper.fg.copy(alpha = 0.28f))
                }
                IconButton(
                    enabled = idx in 0 until toc.size - 1,
                    onClick = { if (idx in 0 until toc.size - 1) goChapter(idx + 1) }
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章",
                        tint = if (idx in 0 until toc.size - 1) paper.fg else paper.fg.copy(alpha = 0.28f))
                }
                IconButton(onClick = { panelOpen = !panelOpen }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "阅读设置", tint = paper.fg)
                }
            }

            if (state.loadingChapter) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp), color = paper.accent, strokeWidth = 2.dp
                    )
                }
                return@Column
            }

            if (chapter == null) {
                EmptyHint("选择一章开始读")
                return@Column
            }

            val downloading = vm.downloadTask(state.openedBook?.bookUrl ?: "") != null

            if (pageMode) {
                PagedReader(
                    chapterText = chapter.text,
                    title = chapter.title,
                    bodySize = bodySize,
                    bodyLine = bodyLine,
                    fg = paper.fg,
                    initialChar = anchorChar,
                    onPageAnchorChanged = { anchorChar = it },
                    onTogglePanel = { panelOpen = !panelOpen },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val third = size.width / 3f
                                when {
                                    offset.x < third -> scope.launch { scroll.animateScrollBy(-size.height * 0.85f) }
                                    offset.x > third * 2 -> scope.launch { scroll.animateScrollBy(size.height * 0.85f) }
                                    else -> panelOpen = !panelOpen
                                }
                            }
                        }
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
                    ChapterImages(chapter.images, chapter.title)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        buildString {
                            append("${state.chapterIndex + 1} / ${state.toc.size} 章")
                            if (downloading) append(" · 已离线缓存")
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

        // ---------------- 底部快捷面板 ----------------
        if (panelOpen) {
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderQuickPanel(
                    settings = cfg,
                    paper = paper,
                    onChange = { vm.notifySettingsChanged() }
                )
            }
        }
    }
}

/**
 * 翻页模式的阅读区。
 *
 * 分页结果用 `remember` 缓存，键包含所有影响排版的参数。
 * 缓存失效时（改字号/转屏）重算，并把 [initialChar] 所在页作为起点 ——
 * 这样「调大字号」不会把你弹回章首。
 *
 * 点击分区：左 1/3 上一页、右 1/3 下一页、中 1/3 开关快捷面板。
 * 到头了不循环 —— 翻页到最后一页继续往右没有反应，而不是跳回第一页
 * （看书时误触跳回第一页是最烦的事）。
 */
@Composable
private fun PagedReader(
    chapterText: String,
    title: String,
    bodySize: androidx.compose.ui.unit.TextUnit,
    bodyLine: androidx.compose.ui.unit.TextUnit,
    fg: androidx.compose.ui.graphics.Color,
    initialChar: Int,
    onPageAnchorChanged: (Int) -> Unit,
    onTogglePanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = bodySize,
        lineHeight = bodyLine
    )

    // 可视区域：屏高减去顶栏、状态栏、底部留白。
    // 这里用 Configuration 估一个保守值，真实测量在 BoxWithConstraints 里。
    var pages by remember { mutableStateOf<List<ChapterPaginator.Page>>(emptyList()) }
    var viewport by remember { mutableStateOf(0 to 0) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size.coerceAtLeast(1) }
    )

    // 分页：viewport 变化或排版参数变化时重算
    LaunchedEffect(chapterText, bodySize, bodyLine, viewport, style) {
        val (w, h) = viewport
        if (w <= 0 || h <= 0) return@LaunchedEffect
        pages = ChapterPaginator.paginate(
            text = chapterText,
            measurer = measurer,
            style = style,
            widthPx = w,
            heightPx = h
        )
    }

    // 分页算好之后跳到 initialChar 所在页（切换模式/改字号时保持阅读位置）
    LaunchedEffect(pages, initialChar) {
        if (pages.isEmpty()) return@LaunchedEffect
        val target = ChapterPaginator.pageOfChar(pages, initialChar)
        if (target != pagerState.currentPage) {
            pagerState.scrollToPage(target.coerceIn(0, pages.size - 1))
        }
    }

    // 翻页时把当前页的起始字符回报给外层，供进度保存/模式切换
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { p ->
            pages.getOrNull(p)?.let { onPageAnchorChanged(it.start) }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wPx = with(density) { (maxWidth - 48.dp).toPx().toInt() }
        val hPx = with(density) { (maxHeight - 24.dp).toPx().toInt() }
        LaunchedEffect(wPx, hPx) {
            if (wPx != viewport.first || hPx != viewport.second) viewport = wPx to hPx
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp
        ) { pageIndex ->
            val page = pages.getOrNull(pageIndex)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageIndex) {
                        detectTapGestures { offset: Offset ->
                            val third = size.width / 3f
                            // ⚠️ 用 scrollToPage（瞬时）而不是 animateScrollToPage。
                            // 实测：animate 有 ~300ms 滑动，快速连点会在动画中途
                            // 被下一次点击打断，结果 20 次点击只翻了几页。
                            // 翻页阅读本来就该干脆到位 —— 纸书翻页也没有过渡动画。
                            when {
                                offset.x < third -> scope.launch {
                                    if (pagerState.currentPage > 0) {
                                        pagerState.scrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                                offset.x > third * 2 -> scope.launch {
                                    val last = (pages.size - 1).coerceAtLeast(0)
                                    if (pagerState.currentPage < last) {
                                        pagerState.scrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                                else -> onTogglePanel()
                            }
                        }
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    if (pageIndex == 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = fg.copy(alpha = 0.75f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        text = if (page != null) {
                            ChapterPaginator.textOfPage(chapterText, page)
                        } else "",
                        fontSize = bodySize,
                        lineHeight = bodyLine,
                        color = fg
                    )
                }

                // 页码
                Text(
                    "${pageIndex + 1} / ${pages.size.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.35f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * 正文插图。
 *
 * 书源给的图片地址（`chapter.images`）直接渲染成自适应宽度的 `AsyncImage`。
 * 之前只把 URL 列出来当文本，轻小说和图源基本没法看。
 *
 * 加载失败不显示破图占位，直接折叠 —— 图源站会 403（缺 Referer），
 * 满屏"加载失败"比不显示更烦人。
 */
@Composable
private fun ChapterImages(images: List<String>, chapterTitle: String) {
    if (images.isEmpty()) return
    val ctx = LocalContext.current
    Spacer(Modifier.height(16.dp))
    images.forEachIndexed { i, url ->
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(url)
                .crossfade(180)
                .build(),
            contentDescription = "$chapterTitle 插图 ${i + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp)
        )
    }
}
