package com.ayanami.reader.data

import android.content.Context
import com.ayanami.reader.core.classify.BookClassifier

/**
 * 全局设置 —— 收口所有开关，单一入口。
 *
 * ### 为什么用 `SharedPreferences` 而不是 JSON 文件
 *
 * 设置要在每个 Composable 里读（字号、主题、R18 开关），
 * 走文件 + 反序列化会让每次重组都可能触发 IO。`SharedPreferences`
 * 首次读之后全在内存里，且系统保证原子性写入。
 *
 * ### R18 开关的安全语义
 *
 * `adultEnabled = false` 时，**上层必须硬过滤**，不能只靠 UI 不显示入口。
 * 见 [allows]：它把「这本书该不该出现在列表里」做成一个纯函数，
 * 书城 / 搜索 / Pixiv 三处都调它，避免某一处忘了过滤。
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    // ---------------- 内容安全 ----------------

    /**
     * R18 内容总开关。
     *
     * 默认 **false**。用户显式打开才能看到成人向内容，
     * 且这个开关同时影响：书城的成人向分类入口、内容分级筛选、
     * 搜索结果的过滤、Pixiv 的 R18 榜单。
     */
    var adultEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADULT, false)
        set(v) = prefs.edit().putBoolean(KEY_ADULT, v).apply()

    // ---------------- 阅读器 ----------------

    /** 字号（sp）。 */
    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 18)
        set(v) = prefs.edit().putInt(KEY_FONT_SIZE, v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** 行距倍数 × 100（避免 SharedPreferences 没有 float 精度顾虑）。 */
    var lineHeightPercent: Int
        get() = prefs.getInt(KEY_LINE_HEIGHT, 170)
        set(v) = prefs.edit().putInt(KEY_LINE_HEIGHT, v.coerceIn(120, 240)).apply()

    /** 阅读背景主题。 */
    var readerTheme: ReaderTheme
        get() = runCatching {
            ReaderTheme.valueOf(prefs.getString(KEY_READER_THEME, ReaderTheme.DAY.name)!!)
        }.getOrDefault(ReaderTheme.DAY)
        set(v) = prefs.edit().putString(KEY_READER_THEME, v.name).apply()

    /** 翻页方式。 */
    var pageMode: PageMode
        get() = runCatching {
            PageMode.valueOf(prefs.getString(KEY_PAGE_MODE, PageMode.SCROLL.name)!!)
        }.getOrDefault(PageMode.SCROLL)
        set(v) = prefs.edit().putString(KEY_PAGE_MODE, v.name).apply()

    /** 打开书籍时自动恢复上次阅读进度。 */
    var restoreProgress: Boolean
        get() = prefs.getBoolean(KEY_RESTORE, true)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE, v).apply()

    // ---------------- 书城 ----------------

    /** 书城默认主分类（存 enum name，避免序号漂移）。 */
    var bookstoreGenre: String
        get() = prefs.getString(KEY_BOOKSTORE_GENRE, "ALL") ?: "ALL"
        set(v) = prefs.edit().putString(KEY_BOOKSTORE_GENRE, v).apply()

    /** 书城默认内容分级：ALL / GENERAL / ADULT。 */
    var contentRating: String
        get() = prefs.getString(KEY_CONTENT_RATING, "ALL") ?: "ALL"
        set(v) = prefs.edit().putString(KEY_CONTENT_RATING, v).apply()

    /** 书城排序。 */
    var sortMode: SortMode
        get() = runCatching {
            SortMode.valueOf(prefs.getString(KEY_SORT, SortMode.DEFAULT.name)!!)
        }.getOrDefault(SortMode.DEFAULT)
        set(v) = prefs.edit().putString(KEY_SORT, v.name).apply()

    // ---------------- Pixiv ----------------

    var pixivCookie: String
        get() = prefs.getString(KEY_PIXIV_COOKIE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PIXIV_COOKIE, v).apply()

    var pixivRefreshToken: String
        get() = prefs.getString(KEY_PIXIV_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PIXIV_TOKEN, v).apply()

    /** 代理地址（空 = 直连）。 */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY, v).apply()

    /**
     * Pixiv 图床镜像域名。
     *
     * 默认 `i.pximg.net`（官方，需 Referer）。国内网络直连不稳时
     * 可切到反代域名，见 [com.ayanami.reader.core.pixiv.PixivImageUrls.KNOWN_MIRRORS]。
     */
    var pixivImageMirror: String
        get() = prefs.getString(KEY_PIXIV_MIRROR, "i.pximg.net") ?: "i.pximg.net"
        set(v) = prefs.edit().putString(KEY_PIXIV_MIRROR, v.trim()).apply()

    /**
     * Pixiv API 镜像前缀（自建反代时填）。
     *
     * 留空 = 直连 `https://www.pixiv.net`。填了就必须是完整前缀，
     * 例如 `https://px.example.com`。
     */
    var pixivApiBase: String
        get() = prefs.getString(KEY_PIXIV_API_BASE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PIXIV_API_BASE, v.trim().trimEnd('/')).apply()

    // ---------------- 纯函数：可见性判定 ----------------

    /**
     * 一本书在当前设置下是否**允许出现**。
     *
     * 这是 R18 边界的唯一判定点 —— 书城、搜索、书架三处都必须过这里。
     * 做成纯函数而不是散落在 UI 里，是因为"某一处忘了过滤"这种 bug
     * 一旦漏出去就是产品事故，而测试可以逐条锁住这个函数。
     */
    fun allows(classification: BookClassifier.Result): Boolean {
        if (!adultEnabled && classification.isAdult) return false
        return when (contentRating) {
            "GENERAL" -> !classification.isAdult
            "ADULT" -> classification.isAdult
            else -> true          // ALL
        }
    }

    enum class ReaderTheme(val label: String) {
        DAY("日间"),
        EYE_CARE("护眼"),
        NIGHT("夜间")
    }

    enum class PageMode(val label: String) {
        SCROLL("上下滚动"),
        PAGE("左右翻页")
    }

    enum class SortMode(val label: String) {
        DEFAULT("综合默认"),
        RECENT("近期更新"),
        WORD_COUNT_DESC("字数倒序"),
        WORD_COUNT_ASC("字数正序")
    }

    companion object {
        const val MIN_FONT = 13
        const val MAX_FONT = 30

        private const val KEY_ADULT = "adult_enabled"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height_pct"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_PAGE_MODE = "page_mode"
        private const val KEY_RESTORE = "restore_progress"
        private const val KEY_BOOKSTORE_GENRE = "bookstore_genre"
        private const val KEY_CONTENT_RATING = "content_rating"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_PIXIV_COOKIE = "pixiv_cookie"
        private const val KEY_PIXIV_TOKEN = "pixiv_token"
        private const val KEY_PROXY = "proxy_url"
        private const val KEY_PIXIV_MIRROR = "pixiv_image_mirror"
        private const val KEY_PIXIV_API_BASE = "pixiv_api_base"
    }
}
