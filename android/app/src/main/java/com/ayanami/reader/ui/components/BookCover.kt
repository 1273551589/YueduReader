package com.ayanami.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * 书籍封面。
 *
 * 书源给不出封面是常态（大量老站 `coverUrl` 规则是空的），
 * 所以**占位图必须好看**，不能是灰色方块：
 *
 * - 有 `coverUrl` → Coil 异步加载，圆角 10dp，`ContentScale.Crop`
 * - 加载中 / 失败 / 无地址 → 用书名首字 + 渐变色块顶替
 *
 * 渐变色的取法：对 `name` 做稳定哈希取色板下标。
 * 这样同一本书每次进来颜色一致，看起来像"它本来就有这张封面"。
 */
@Composable
fun BookCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = 76.dp,
    height: Dp = 104.dp,
    corner: Dp = 9.dp
) {
    val t = LocalReaderTokens.current
    val shape = RoundedCornerShape(corner)
    val context = LocalContext.current
    var failed by remember(coverUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(placeholderBrush(title)),
        contentAlignment = Alignment.Center
    ) {
        // 占位层：首字 + 书名（被图片盖住时不可见，作为 fallback 一直在）
        PlaceholderArt(title = title, textColor = t.surface)

        if (!coverUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(180)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true }
            )
        }
    }
}

/** 没有封面时的占位图案：首字大字 + 微妙的斜向高光。 */
@Composable
private fun PlaceholderArt(title: String, textColor: Color) {
    val initial = title.trim().firstOrNull()?.toString() ?: "书"
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = initial,
            color = textColor.copy(alpha = 0.92f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 按书名稳定取色。
 *
 * 为什么用 `hashCode` 而不是随机：同一本书在书架、搜索、详情三处
 * 必须同色，否则用户会以为是三本不同的书。
 */
private fun placeholderBrush(title: String): Brush {
    val palette = listOf(
        Color(0xFF5B7FBF), Color(0xFF7A6BA8), Color(0xFF4E8C7E),
        Color(0xFFB07A5B), Color(0xFF8C6A7E), Color(0xFF5A7A96),
        Color(0xFF9A7B4F), Color(0xFF6C7F5B), Color(0xFF8A6B8F),
        Color(0xFF5F7C8A)
    )
    val idx = stableHash(title) % palette.size
    val c = palette[idx]
    return Brush.linearGradient(
        colors = listOf(c, c.copy(alpha = 0.78f), c.copy(alpha = 0.62f))
    )
}

/**
 * 稳定哈希。
 *
 * `String.hashCode()` 在 JVM 上是稳定的，但它对相似字符串分布不好
 * （"第1章"/"第2章" 常撞同一格）。这里用 FNV-1a 的低位混合。
 */
private fun stableHash(s: String): Int {
    var h = 0x811C9DC5.toInt()
    for (c in s) {
        h = h xor c.code
        h *= 0x01000193
    }
    return (h and 0x7FFFFFFF)
}
