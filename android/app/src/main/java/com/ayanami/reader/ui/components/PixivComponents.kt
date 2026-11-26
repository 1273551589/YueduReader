package com.ayanami.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ayanami.reader.core.pixiv.PixivIllust
import com.ayanami.reader.core.pixiv.PixivNovel
import com.ayanami.reader.core.pixiv.PixivUser
import com.ayanami.reader.ui.theme.CardTint
import com.ayanami.reader.ui.theme.LocalReaderTokens
import com.ayanami.reader.ui.theme.PillIdleBg
import com.ayanami.reader.ui.theme.PillSelectedBg

/**
 * Pixiv 相关的 UI 组件。
 *
 * ### 所有图片都走 Coil + PixivImageInterceptor
 *
 * 图床需要 `Referer: https://www.pixiv.net/`，否则 403。
 * 拦截器在 App 启动时挂到全局 ImageLoader，见 [com.ayanami.reader.PixivImageLoader]。
 * 所以这里的 `AsyncImage` 不需要额外加 header —— 加了反而会和拦截器重复。
 */

/** 胶囊：选中态浅紫底 + 深紫字，未选中浅灰底 + 灰字。 */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalReaderTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PillSelectedBg else PillIdleBg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) t.primary else t.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * 排行榜大图卡（横向滚动用）。
 *
 * 图片底部叠一层透明黑渐变，标题和作者压在渐变上 ——
 * 纯白文字直接放图片上，遇到浅色图会完全看不见。
 */
@Composable
fun IllustRankCard(
    illust: PixivIllust,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 156.dp
) {
    val t = LocalReaderTokens.current
    Column(
        modifier = modifier
            .width(size)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
        ) {
            PixivImage(
                url = illust.thumb,
                contentDescription = illust.title,
                modifier = Modifier.fillMaxSize()
            )
            // 底部渐变遮罩：保证白字在任何底图上都能读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            0.75f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Black.copy(alpha = 0.72f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    illust.title.ifBlank { "无题" },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundAvatar(url = illust.authorAvatar, size = 16.dp, name = illust.authorName)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        illust.authorName.ifBlank { "—" },
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            // 排名角标
            if (illust.rank in 1..99) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("#${illust.rank}", color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 小说排行卡片 —— 核心信息层级。
 *
 * 从上到下：系列名 → 标题（2 行）→ 作者 → tag 流 → 三个徽章。
 * 这个顺序是有讲究的：用户扫视卡片时先看标题决定要不要读，
 * 再看作者确认是不是熟悉的，最后才看数据。把徽章放前面会打断扫视。
 */
@Composable
fun NovelRankCard(
    novel: PixivNovel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalReaderTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        // 封面
        Box(
            modifier = Modifier
                .width(78.dp)
                .height(108.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardTint)
        ) {
            PixivImage(
                url = novel.coverUrl,
                contentDescription = novel.title,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (novel.seriesTitle.isNotBlank()) {
                Text(
                    novel.seriesTitle,
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                novel.title.ifBlank { "无题" },
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = t.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (novel.authorName.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "by ${novel.authorName}",
                    fontSize = 11.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (novel.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TagFlow(tags = novel.tags.take(6))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Badge("#${novel.rank.coerceAtLeast(1)}")
                if (novel.wordCount > 0) Badge("${formatCount(novel.wordCount)}字")
                if (novel.bookmarkCount > 0) Badge("♡ ${formatCount(novel.bookmarkCount)}")
            }
        }
    }
}

/** 浅紫圆角小徽章（排名 / 字数 / 收藏）。 */
@Composable
fun Badge(text: String) {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PillSelectedBg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = t.primary, fontWeight = FontWeight.Medium)
    }
}

/**
 * 自动换行的 tag 流。
 *
 * 没引 FlowRow 依赖，用固定列宽的手写换行 —— 代价是每行最后一个
 * tag 可能没填满，但避免了为一个组件加一整个实验性 API 依赖。
 */
@Composable
fun TagFlow(tags: List<String>, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val t = LocalReaderTokens.current
    Column(modifier = modifier) {
        var line = ArrayList<String>()
        var lineWidth = 0f
        val perTagWeight = 0

        // 简化：每行最多放 4 个，够用且不会溢出
        val rows = tags.chunked(4).take(maxLines)
        rows.forEachIndexed { idx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { tag ->
                    Text(
                        "#$tag",
                        fontSize = 10.sp,
                        color = t.textSecondary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(62.dp)
                    )
                }
                // 最后一行如果还有更多 tag，补一个省略
                if (idx == maxLines - 1 && tags.size > maxLines * 4) {
                    Text("…", fontSize = 10.sp, color = t.textSecondary)
                }
            }
            if (idx < rows.size - 1) Spacer(Modifier.height(2.dp))
        }
    }
}

/** 圆形头像。加载失败时用首字占位。 */
@Composable
fun RoundAvatar(url: String, size: Dp = 28.dp, name: String = "") {
    val t = LocalReaderTokens.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PillSelectedBg),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNotBlank()) {
            PixivImage(
                url = url,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                name.firstOrNull()?.toString() ?: "?",
                fontSize = (size.value / 2.4f).sp,
                color = t.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 双列 Tag 墙卡片：方图 + 底部渐变 + `#原Tag` 与中文译名。
 */
@Composable
fun TagWallCard(
    tag: String,
    coverUrl: String,
    translated: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ⚠️ 必须用 height 而不是 aspectRatio。
    // 在 LazyVerticalGrid 里，item 的高度约束在测量前是无限的，
    // aspectRatio(1f) 会先按宽度算出高度 —— 但那一步依赖的约束
    // 在 grid 内层拿不到，实测结果是高度算出 0（格子可见但图是空白）。
    // 用固定高度最稳，视觉上仍是方形（列宽约等于这个高度）。
    Box(
        modifier = modifier
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        // 先铺一层浅灰底，图未加载时不至于像"裂图"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PillIdleBg)
        )
        PixivImage(
            url = coverUrl,
            contentDescription = tag,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.70f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                "#$tag",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!translated.isNullOrBlank() && translated != tag) {
                Text(
                    translated,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 画师卡片：3 连斜拼代表作横幅 + 居中头像与昵称。
 *
 * 3 连拼的实现：三张图各占 1/3 宽，用 `graphicsLayer { rotationZ }`
 * 之外的更简单做法 —— 直接并排 + 左右两张做轻微斜切遮罩。
 * 这里用并排 + 圆角裁剪，视觉上也够接近，且不会因旋转产生白边。
 */
@Composable
fun ArtistCard(
    user: PixivUser,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalReaderTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        // 背景：3 连拼
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(3) { i ->
                val url = user.works.getOrNull(i) ?: user.works.lastOrNull().orEmpty()
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    PixivImage(
                        url = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        // 压暗，保证前景可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
        )
        // 居中头像 + 昵称
        //
        // ⚠️ 这一层必须让点击穿透到外层 Box 的 clickable。
        // 实测：不加 `pointerInput` 时，点在头像/昵称上**不会**触发 onClick
        // （Compose 里内层可组合会截获命中测试），用户点了「没反应」。
        // 用 `clickable` 显式再绑一次是最稳的做法。
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RoundAvatar(url = user.avatar, size = 48.dp, name = user.name)
            Spacer(Modifier.height(6.dp))
            Text(
                user.name.ifBlank { "画师 ${user.id}" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 分区标题行：「图标 + 加粗标题 + 右侧 ›」。
 */
@Composable
fun SectionHeaderRow(
    title: String,
    onMore: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val t = LocalReaderTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = t.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (onMore != null) {
            Text(
                "›",
                fontSize = 20.sp,
                color = t.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onMore() }
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

/** Pixiv 图片：统一走 Coil（拦截器已注入 Referer）。 */
@Composable
fun PixivImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (url.isBlank()) {
        Box(modifier = modifier.background(PillIdleBg))
        return
    }
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}

/** 数字格式化：11163 → 1.1万，989 → 989。 */
fun formatCount(n: Int): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000f)
    n >= 10_000 -> String.format("%.1f万", n / 10_000f)
    else -> n.toString()
}
