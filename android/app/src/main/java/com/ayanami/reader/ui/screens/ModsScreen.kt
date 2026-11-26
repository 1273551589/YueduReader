package com.ayanami.reader.ui.screens

import com.ayanami.reader.ui.components.statusBarTopPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.components.EmptyHint
import com.ayanami.reader.ui.components.Pill
import com.ayanami.reader.ui.theme.LocalReaderTokens

@Composable
fun ModsScreen(
    vm: ReaderViewModel,
    state: ReaderUiState,
    onBack: () -> Unit
) {
    val t = LocalReaderTokens.current
    val ctx = LocalContext.current
    var showErrors by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val tmp = java.io.File(ctx.cacheDir, "mod_${System.currentTimeMillis()}.zip")
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            vm.installMod(tmp.absolutePath)
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
                Text("MOD", style = MaterialTheme.typography.headlineMedium,
                    color = t.textPrimary, fontWeight = FontWeight.Bold)
                Text("声明式 · 不含可执行代码", style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.primary)
            ) { Text("装 MOD 包（zip）") }
            if (vm.modErrors.isNotEmpty()) {
                Button(
                    onClick = { showErrors = !showErrors },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.textSecondary.copy(alpha = 0.15f),
                        contentColor = t.textSecondary
                    )
                ) { Text("${vm.modErrors.size} 条警告") }
            }
        }

        if (showErrors) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                vm.modErrors.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = t.accent)
                }
            }
        }

        val mods = vm.mods.mods
        if (mods.isEmpty()) {
            EmptyHint(
                "还没有 MOD。\n\n" +
                    "一个 MOD 是一个文件夹，至少含 mod.json：\n" +
                    "{ \"id\":\"my-theme\", \"type\":\"theme\", \"entry\":\"theme.json\" }\n\n" +
                    "type 支持：theme / sources / categories"
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(mods) { mod ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 5.dp)
                            .background(t.surface, RoundedCornerShape(t.cornerRadius))
                            .padding(15.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(mod.manifest.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = t.textPrimary, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${mod.manifest.id} · v${mod.manifest.version}" +
                                        if (mod.manifest.author.isNotBlank()) " · ${mod.manifest.author}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = t.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Pill(mod.manifest.type.name, t.primary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when (mod.manifest.type) {
                                com.ayanami.reader.mod.ModLoader.ModType.theme ->
                                    "主题令牌：" + listOfNotNull(
                                        mod.theme?.primary?.let { "primary" },
                                        mod.theme?.background?.let { "background" },
                                        mod.theme?.cornerRadius?.let { "圆角" }
                                    ).joinToString("、").ifBlank { "无" }
                                com.ayanami.reader.mod.ModLoader.ModType.sources ->
                                    "书源包" + if (mod.sourceJson != null) "（已并入）" else ""
                                com.ayanami.reader.mod.ModLoader.ModType.categories ->
                                    "分类映射 ${mod.categoryMapping.size} 条"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = t.textSecondary
                        )
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}
