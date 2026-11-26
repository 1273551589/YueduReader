package com.ayanami.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayanami.reader.mod.ModLoader

/**
 * 主题 = CSS 变量思路的 Compose 版本。
 *
 * MOD 只能换这里的令牌，不能改代码 —— 所以 [ReaderTokens] 是唯一入口。
 */
data class ReaderTokens(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color,
    val accent: Color,
    val cornerRadius: androidx.compose.ui.unit.Dp = 14.dp,
    val contentPadding: androidx.compose.ui.unit.Dp = 22.dp
)

/**
 * 默认令牌：Material 3 柔和浅紫 + 极简留白。
 *
 * 为什么换成浅紫而不是原来的暖灰：整 App 的品牌色偏暖灰时，
 * 「选中态」只能靠加粗/描边区分，视觉上找不到焦点。
 * 浅紫（`#6B5BD2`）在浅灰白底（`#FAFAFC`）上有明确的色相差异，
 * 同时饱和度足够低，不会像亮蓝那样刺眼。
 *
 * 三档色彩各有分工（对应 PixivViewer 的胶囊体系）：
 * - [primary] 深紫：选中态文字/图标、强调按钮
 * - [surfaceTint] 浅紫底：选中态胶囊背景
 * - [pillIdle] 浅灰底：未选中态胶囊背景
 */
val LocalReaderTokens = staticCompositionLocalOf {
    ReaderTokens(
        background = Color(0xFFFAFAFC),
        surface = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF1F1D2B),
        textSecondary = Color(0xFF8B88A0),
        primary = Color(0xFF6B5BD2),
        accent = Color(0xFFE8622C)
    )
}

/**
 * 胶囊体系的两种底色。
 *
 * 单独抽出来而不是塞进 [ReaderTokens]：MOD 主题只该换主色，
 * 不该让 MOD 有机会把选中/未选中做得看不出区别。
 */
val PillSelectedBg = Color(0xFFEAE4FA)
val PillIdleBg = Color(0xFFF1F0F5)

/** 卡片浅紫底（用于「特辑」这类强调卡片）。 */
val CardTint = Color(0xFFF5F3FA)

val DarkTokens = ReaderTokens(
    background = Color(0xFF121212),
    surface = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFEDEDED),
    textSecondary = Color(0xFF8E8E93),
    primary = Color(0xFF6D9CFF),
    accent = Color(0xFFFF8A50)
)

private fun lightScheme(t: ReaderTokens) = lightColorScheme(
    primary = t.primary,
    onPrimary = Color.White,
    background = t.background,
    onBackground = t.textPrimary,
    surface = t.surface,
    onSurface = t.textPrimary,
    surfaceVariant = t.surface,
    onSurfaceVariant = t.textSecondary,
    secondary = t.accent
)

private fun darkScheme(t: ReaderTokens) = darkColorScheme(
    primary = t.primary,
    onPrimary = Color.Black,
    background = t.background,
    onBackground = t.textPrimary,
    surface = t.surface,
    onSurface = t.textPrimary,
    surfaceVariant = t.surface,
    onSurfaceVariant = t.textSecondary,
    secondary = t.accent
)

/** 中文优先的字体栈；大标题留白，内容密度接近 Notion。 */
private val ReaderTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
fun ReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    modTheme: ModLoader.ThemeTokens? = null,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkTokens else LocalReaderTokens.current
    val tokens = base.copy(
        primary = modTheme?.primary ?: base.primary,
        background = modTheme?.background ?: base.background,
        surface = modTheme?.surface ?: base.surface,
        textPrimary = modTheme?.textPrimary ?: base.textPrimary,
        textSecondary = modTheme?.textSecondary ?: base.textSecondary,
        accent = modTheme?.accent ?: base.accent,
        cornerRadius = modTheme?.cornerRadius?.dp ?: base.cornerRadius,
        contentPadding = modTheme?.spacing?.dp ?: base.contentPadding
    )

    CompositionLocalProvider(LocalReaderTokens provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(tokens) else lightScheme(tokens),
            typography = ReaderTypography,
            content = content
        )
    }
}
