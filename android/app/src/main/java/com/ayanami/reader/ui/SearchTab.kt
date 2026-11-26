package com.ayanami.reader.ui

/**
 * 搜索页的四个子 Tab。
 *
 * 放在 ui 包（而不是 screens 包）是为了让 [ReaderViewModel] 和导航层
 * 都能引用 —— 「从 Pixiv 点 Tag 跳到搜索页」需要跨页面指定 Tab。
 */
enum class SearchTab(val label: String) {
    SOURCE("书源小说"),
    PIXIV_ILLUST("Pixiv插画·漫画"),
    PIXIV_NOVEL("Pixiv小说"),
    PIXIV_USER("Pixiv画师")
}
