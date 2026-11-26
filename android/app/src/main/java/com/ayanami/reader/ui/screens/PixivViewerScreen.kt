package com.ayanami.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 高清大图浏览器。
 *
 * ### 为什么用黑底
 *
 * 看图时任何浅色 UI 都会干扰对画面明度的判断（尤其插画）。
 * 全黑底 + 半透明信息板是各家图片浏览器的共识做法。
 *
 * ### 手势优先级
 *
 * 单指左右滑 → 换图（Pager）
 * 双指 → 缩放当前图
 *
 * 两者会抢手势，所以缩放用 `detectTransformGestures` 且只在
 * scale > 1 时才消费事件 —— 未放大时左右滑交给 Pager。
 */
@Composable
fun PixivViewerScreen(
    vm: com.ayanami.reader.ui.ReaderViewModel,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    val list = vm.pixivState.viewerIllusts
    if (list.isEmpty()) {
        onBack()
        return
    }
    val pager = rememberPagerState(
        initialPage = vm.pixivState.viewerIndex,
        pageCount = { list.size }
    )
    // 当前作品的所有页（多图作品）
    var pageInWork by remember(pager.currentPage) { mutableStateOf(0) }
    val current = list.getOrNull(pager.currentPage)
    val pages = current?.pageUrls.orEmpty()
    val shown = pages.getOrNull(pageInWork) ?: current?.coverUrl.orEmpty()

    var scale by remember(pager.currentPage, pageInWork) { mutableFloatStateOf(1f) }
    var offsetX by remember(pager.currentPage, pageInWork) { mutableFloatStateOf(0f) }
    var offsetY by remember(pager.currentPage, pageInWork) { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 图片区 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pager.currentPage, pageInWork) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4f)
                        if (newScale > 1f) {
                            scale = newScale
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            // 回到 1x 时复位，避免图卡在边缘
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        ) {
            AsyncImage(
                model = shown,
                contentDescription = current?.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            // 单指点按切换信息板
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, _, _ -> }
                    }
            )
        }

        // ---- 多图作品内的翻页 ----
        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pageInWork > 0) {
                    IconButton(onClick = { pageInWork--; scale = 1f }) {
                        Text("‹", color = Color.White, fontSize = 30.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pageInWork < pages.size - 1) {
                    IconButton(onClick = { pageInWork++; scale = 1f }) {
                        Text("›", color = Color.White, fontSize = 30.sp)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 132.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    "${pageInWork + 1}/${pages.size}",
                    color = Color.White, fontSize = 11.sp
                )
            }
        }

        // ---- 顶部返回 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(top = 34.dp, start = 4.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                current?.title?.ifBlank { "无题" } ?: "",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${pager.currentPage + 1} / ${list.size}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }

        // ---- 底部信息板 ----
        // 取一个非空局部引用，避免满屏 `current?.` / `current!!`
        val info = current
        if (showInfo && info != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = info.authorAvatar,
                        contentDescription = info.authorName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.DarkGray)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        info.authorName.ifBlank { "—" },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    if (info.bookmarkCount > 0) {
                        Text(
                            "♡ ${com.ayanami.reader.ui.components.formatCount(info.bookmarkCount)}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    }
                }
                if (info.tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(info.tags) { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text("#$tag", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = { onSave(shown) }) {
                        Text("保存原图", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
