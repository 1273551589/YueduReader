package com.ayanami.reader.core.model

/** 搜索结果 / 发现页条目。 */
data class SearchBook(
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var intro: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var wordCount: String? = null,
    /** 详情页地址（已解析成绝对地址）。 */
    var bookUrl: String = "",
    /** 命中此条的书源。 */
    var origin: String = "",
    var originName: String = ""
) {
    /** 跨源去重用的稳定键。 */
    val stableKey: String get() = "$name\u0000$author"
}

/** 书籍详情。 */
data class Book(
    var name: String = "",
    var author: String = "",
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var wordCount: String? = null,
    var tocUrl: String = "",
    var bookUrl: String = "",
    var origin: String = "",
    var originName: String = ""
)

/** 目录条目。 */
data class BookChapter(
    var title: String = "",
    var url: String = "",
    var index: Int = 0,
    var isVolume: Boolean = false,
    /** 正文阶段是否已抓到过内容（避免重复请求）。 */
    var cachedLength: Int = 0
)

/** 正文。 */
data class TextChapter(
    var title: String = "",
    var url: String = "",
    /** 纯文本正文。 */
    var text: String = "",
    /** 图片型源的图片列表。 */
    var images: List<String> = emptyList()
)
