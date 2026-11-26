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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.ui.ReaderUiState
import com.ayanami.reader.ui.ReaderViewModel
import com.ayanami.reader.ui.adultEnabled
import com.ayanami.reader.ui.components.ScreenHeader
import com.ayanami.reader.ui.theme.LocalReaderTokens

/**
 * Pixiv 端口。
 *
 * ⚠️ 这是 v0.5.0 的**占位骨架**，网络层还没接。
 *
 * 我不打算做一个「看起来能用其实是假的」页面 ——
 * 所以这里明确标注状态，并且把要做的三件事写清楚，
 * 而不是放几个假封面骗点击。
 *
 * v0.7.0 的实现要点（已规划）：
 * - `OkHttpClient` 拦截器对 `*.pximg.net` 注入 `Referer: https://www.pixiv.net/`
 *   （缺这个头，图床直接 403）
 * - 热门小说：`/ajax/top/novel/{mode}?mode={daily|weekly|monthly}`，
 *   R18 榜单要带 Cookie。正文解析 Pixiv 特有语法：
 *   `[newpage]` 分页、`[chapter:标题]`、`[[rb:汉字>假名]]` 注音
 * - 热门图片：`/ajax/top/illust?mode=...`，双列瀑布流 + 高清原图
 * - 直连绕过：部分网络环境下 pixiv.net 被 SNI 阻断，需要 DoH 或自定义 DNS
 */
@Composable
fun PixivScreen(vm: ReaderViewModel, state: ReaderUiState) {
    val t = LocalReaderTokens.current
    vm.settingsTick()
    val adultOn = vm.adultEnabled
    val cookieReady = vm.settings.pixivCookie.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(t.background)) {
        StatusBarSpacer()
        ScreenHeader(
            title = "Pixiv",
            subtitle = if (cookieReady) "已配置登录" else "未配置登录"
        )

        // 榜单模式（R18 开关控制是否出现 R18 项）
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            val modes = buildList {
                add("日榜")
                add("周榜")
                add("月榜")
                if (adultOn) { add("R18 日榜"); add("R18 周榜") }
            }
            items(modes) { m ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(t.textSecondary.copy(alpha = 0.09f))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(m, fontSize = 13.sp, color = t.textSecondary)
                }
            }
        }

        Spacer(Modifier.height(30.dp))

        // 状态说明 —— 明确告诉用户还没接，而不是放假数据
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(RoundedCornerShape(t.cornerRadius))
                .background(t.surface)
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFC8871E))
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "v0.7.0 交付",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC8871E),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pixiv 端口还在做，这一版先把框架和入口留出来。",
                    style = MaterialTheme.typography.titleMedium,
                    color = t.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "要做完的三件事：\n" +
                        "① 图床 Referer 注入 —— 缺这个头 pximg.net 直接 403\n" +
                        "② 热门小说榜单 + Pixiv 特有正文语法（[newpage] / [chapter:] / [[rb:汉字>假名]]）\n" +
                        "③ 热门图片双列瀑布流 + 高清原图保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.textSecondary
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (cookieReady) t.primary.copy(alpha = 0.08f)
                        else Color(0xFFC8871E).copy(alpha = 0.09f))
                        .padding(11.dp)
                ) {
                    Text(
                        if (cookieReady)
                            "登录信息已配置。这版还不会用它发请求。"
                        else "还没有 Cookie。去「设置 → Pixiv」填 PHPSESSID 或 Refresh Token。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cookieReady) t.primary else Color(0xFFC8871E)
                    )
                }
            }
        }
    }
}
