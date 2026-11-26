package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.ayanami.reader.core.model.BookChapter
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.DownloadMode
import com.ayanami.reader.ui.cachedCount
import com.ayanami.reader.ui.downloadTask
import com.ayanami.reader.ui.startDownload
import com.ayanami.reader.ui.pauseDownload
import com.ayanami.reader.ui.exportTxt
import com.ayanami.reader.ui.alternateSources
import com.ayanami.reader.ui.switchToSource
import com.ayanami.reader.ui.components.DividerLine
import com.ayanami.reader.ui.components.BookCover
import com.ayanami.reader.ui.components.DownloadBar
import com.ayanami.reader.ui.components.SourceSwitchBar
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.ListRow
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun BookDetailScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit,
    onRead: (BookChapter, Int) -> Unit
) {
    val t = LocalReaderTokens.current
    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
            }
            Text(
                state.openedBook?.name ?: "详情",
                style = MaterialTheme.typography.titleLarge,
                color = t.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (state.loadingToc) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), color = t.primary, strokeWidth = 2.dp)
            }
            return@Column
        }

        val book = state.openedBook
        if (book == null) {
            EmptyHint("没有打开的书")
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        BookCover(
                            coverUrl = book.coverUrl,
                            title = book.name,
                            width = 96.dp,
                            height = 132.dp,
                            corner = 11.dp
                        )
                        Spacer(Modifier.width(15.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(book.author.ifBlank { "未知作者" },
                                style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Pill(book.originName, t.primary)
                                book.kind?.takeIf { it.isNotBlank() }?.let {
                                    Pill(it.take(12), t.textSecondary)
                                }
                            }
                            book.wordCount?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("字数 $it", style = MaterialTheme.typography.bodySmall,
                                    color = t.textSecondary)
                            }
                            book.lastChapter?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("最新 $it", style = MaterialTheme.typography.bodySmall,
                                    color = t.accent, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    book.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                        Spacer(Modifier.height(14.dp))
                        Text(intro, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                    }
                    Spacer(Modifier.height(16.dp))

                    // ---- 离线缓存 ----
                    val cached = vm.cachedCount(book, state.toc.size)
                    DownloadBar(
                        cached = cached,
                        total = state.toc.size,
                        task = vm.downloadTask(book.bookUrl),
                        onDownloadAll = {
                            vm.startDownload(book, state.toc, DownloadMode.ALL)
                        },
                        onDownloadNext = {
                            vm.startDownload(book, state.toc, DownloadMode.NEXT_N, count = 50)
                        },
                        onPause = { vm.pauseDownload(book) },
                        onExport = { vm.exportTxt(book, state.toc) }
                    )

                    // ---- 跨源切换 ----
                    SourceSwitchBar(
                        alternates = vm.alternateSources(),
                        onSwitch = { targetUrl ->
                            val key = "${book.name}\u0000${book.author}"
                            val hit = state.hits.firstOrNull {
                                "${it.name}\u0000${it.author}" == key
                            }
                            if (hit != null) {
                                vm.switchToSource(hit, targetUrl, state.chapterIndex.coerceAtLeast(0))
                            }
                        }
                    )

                    Spacer(Modifier.height(18.dp))
                    Text(
                        "目录 · ${state.toc.size} 章",
                        style = MaterialTheme.typography.titleMedium,
                        color = t.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            itemsIndexed(state.toc) { index, chapter ->
                ListRow(
                    title = chapter.title,
                    subtitle = if (chapter.isVolume) "卷" else null,
                    onClick = { onRead(chapter, index) }
                )
                DividerLine()
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
