package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun SourcesScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit
) {
    val t = LocalReaderTokens.current
    var showImport by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 复制到缓存再读，避免 SAF 权限丢失
            val tmp = java.io.File(ctx.cacheDir, "import_${System.currentTimeMillis()}.json")
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            vm.importSourcesFromFile(tmp.absolutePath)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = statusBarTopPadding() + 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text("书源", style = MaterialTheme.typography.headlineMedium,
                    color = t.textPrimary, fontWeight = FontWeight.Bold)
                Text(
                    if (state.checkingAll) "体检中 ${state.checkProgress?.first ?: 0}/${state.checkProgress?.second ?: 0}"
                    else "${state.sources.size} 条 · 串行体检，每域名 ≥3s",
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary
                )
            }
            IconButton(enabled = !state.checkingAll, onClick = { vm.checkAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "全部重测", tint = t.primary)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.primary)
            ) { Text("选文件导入") }
            TextButton(onClick = { showImport = !showImport }) { Text("粘贴 JSON") }
        }

        if (showImport) {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(170.dp),
                placeholder = { Text("把 Legado 导出的 JSON 粘贴在这里", color = t.textSecondary) },
                shape = RoundedCornerShape(t.cornerRadius)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showImport = false; pasteText = "" }) { Text("取消") }
                TextButton(onClick = {
                    vm.importSources(pasteText)
                    pasteText = ""; showImport = false
                }) { Text("导入") }
            }
        }

        if (state.sources.isEmpty()) {
            EmptyHint("还没有书源。\n导入 Legado 格式 JSON，或装一个带书源包的 MOD。")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.sources, key = { it.bookSourceUrl }) { src ->
                    SourceRow(
                        src = src,
                        check = state.checks[src.bookSourceUrl],
                        failCount = state.failureCounts[src.bookSourceUrl] ?: 0,
                        onToggle = { vm.toggleSource(src.bookSourceUrl, it) },
                        onCheck = { vm.checkOne(src) },
                        onDelete = { vm.deleteSource(src.bookSourceUrl) }
                    )
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
private fun SourceRow(
    src: BookSource,
    check: SourceChecker.CheckResult?,
    failCount: Int,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit
) {
    val t = LocalReaderTokens.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 5.dp)
            .background(t.surface, RoundedCornerShape(t.cornerRadius))
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(src.bookSourceName, style = MaterialTheme.typography.titleMedium,
                    color = t.textPrimary, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    src.bookSourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = src.bookSourceEnabled, onCheckedChange = onToggle)
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (check == null) {
                Pill("未验证", t.textSecondary)
            } else {
                if (check.warnings.isNotEmpty()) {
                    Pill("可读·有警告", Color(0xFFC8871E))
                } else {
                    Pill(check.status.label, statusColor(check.status, t))
                }
                Pill("${check.elapsedMs}ms", t.textSecondary)
                if (check.stuckAt != "done") Pill("卡在 ${check.stuckAt}", t.accent)
                // 连续失败 3 次 → 建议删除（方案 §4.2）
                if (failCount >= 3) Pill("连续失败 $failCount 次，建议删除", t.accent)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCheck) { Text("测", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                if (confirmDelete) onDelete() else confirmDelete = true
            }) {
                Text(
                    if (confirmDelete) "确认删除" else "删",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (confirmDelete) t.accent else t.textSecondary
                )
            }
        }
        if (check != null && check.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(check.detail, style = MaterialTheme.typography.bodySmall,
                color = t.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        check?.sampleBook?.let {
            Text("抽样：$it", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        }

        // ---- 四段校验结果（v0.4.0）----
        // 以前只有一个绿标/红标，用户不知道是哪一步挂了。
        // 现在把 搜索 → 详情 → 目录 → 正文 逐段摊开，一眼看出卡在哪。
        if (check != null && check.stages.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                check.stages.forEach { st ->
                    StageChip(
                        label = shortStageLabel(st.name),
                        passed = st.passed,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            val failed = check.stages.lastOrNull { !it.passed }
            if (failed != null && failed.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${stageLabel(failed.name)}：${failed.note}",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ---- 具体哪条规则出错 ----
        // 优先取致命错误；没有则取警告里的（能跑通但字段错的情况）
        val offending: Pair<String, com.ayanami.reader.core.rule.RuleError>? =
            check?.error?.offendingRule?.takeIf { it.isNotBlank() }?.let { check.stuckAt to check.error }
                ?: check?.warnings?.firstOrNull { it.second.offendingRule?.isNotBlank() == true }

        offending?.let { (stage, err) ->
            Spacer(Modifier.height(8.dp))
            Text(
                "出错的规则 · ${stageLabel(stage)}",
                style = MaterialTheme.typography.labelSmall,
                color = t.accent
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(t.accent.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    err.offendingRule ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textPrimary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        check?.error?.let { err ->
            val stage = err.stageHint
            if (!stage.isNullOrBlank() && check.stuckAt != "done") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "位置：${stageLabel(stage)} · ${err.javaClass.simpleName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.textSecondary
                )
            }
        }
    }
}

/**
 * 四段校验的单个小药丸：通过=绿底，失败=橙底。
 * 宽度由 weight(1f) 均分，四段并排一行。
 */
@Composable
private fun StageChip(label: String, passed: Boolean, modifier: Modifier = Modifier) {
    val t = LocalReaderTokens.current
    val color = if (passed) Color(0xFF2E9E5B) else t.accent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (passed) 0.12f else 0.14f))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

private fun statusColor(s: SourceChecker.UiStatus, t: com.ayanami.reader.ui.theme.ReaderTokens): Color =
    when (s) {
        SourceChecker.UiStatus.READABLE -> Color(0xFF2E9E5B)
        SourceChecker.UiStatus.CONNECT_FAILED -> Color(0xFFD04A3A)
        SourceChecker.UiStatus.NO_CONTENT -> Color(0xFFC8871E)
        SourceChecker.UiStatus.NEEDS_LOGIN -> Color(0xFF8E5BD1)
        SourceChecker.UiStatus.UNSUPPORTED -> t.textSecondary
        SourceChecker.UiStatus.UNVERIFIED -> t.textSecondary
    }

/** 四段校验药丸用的短标签（一行放得下四个）。 */
private fun shortStageLabel(stage: String): String = when {
    stage.startsWith("search") -> "搜索"
    stage.startsWith("bookInfo") -> "详情"
    stage.startsWith("toc") -> "目录"
    stage.startsWith("content") -> "正文"
    stage.startsWith("explore") -> "发现"
    else -> stage
}

/** 把规则里的阶段串翻成人话，用户才知道该改哪个字段。 */
private fun stageLabel(stage: String): String = when {
    stage.startsWith("search") -> "搜索规则（ruleSearch）"
    stage.startsWith("bookInfo") -> "详情规则（ruleBookInfo）"
    stage.startsWith("toc") -> "目录规则（ruleToc）"
    stage.startsWith("content") -> "正文规则（ruleContent）"
    stage.startsWith("explore") -> "发现规则（ruleExplore）"
    else -> stage
}
