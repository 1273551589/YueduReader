package com.ayanami.reader.core.model

import com.google.gson.annotations.SerializedName

/**
 * Legado 书源实体。
 *
 * 字段与 Legado `BookSource` 对齐（顶层 22 项 + 6 个 rule 子对象）。
 * 未知字段在导出时原样保留 —— 见 [rawJson]。
 */
data class BookSource(
    @SerializedName("bookSourceUrl") var bookSourceUrl: String = "",
    @SerializedName("bookSourceName") var bookSourceName: String = "",
    @SerializedName("bookSourceGroup") var bookSourceGroup: String? = null,
    @SerializedName("bookSourceType") var bookSourceType: Int = 0,          // 0 文本 1 图片 2 音频 3 文件
    @SerializedName("bookSourceComment") var bookSourceComment: String? = null,
    @SerializedName("bookSourceEnabled") var bookSourceEnabled: Boolean = true,
    @SerializedName("bookUrlPattern") var bookUrlPattern: String? = null,
    @SerializedName("customOrder") var customOrder: Int = 0,
    @SerializedName("enabledExplore") var enabledExplore: Boolean = true,
    @SerializedName("enabledCookieJar") var enabledCookieJar: Boolean? = null,
    @SerializedName("lastUpdateTime") var lastUpdateTime: Long = 0,
    @SerializedName("respondTime") var respondTime: Long = 0,
    @SerializedName("weight") var weight: Int = 0,
    @SerializedName("header") var header: String? = null,
    @SerializedName("loginUrl") var loginUrl: String? = null,
    @SerializedName("loginUi") var loginUi: String? = null,
    @SerializedName("loginCheckJs") var loginCheckJs: String? = null,
    @SerializedName("concurrentRate") var concurrentRate: String? = null,
    @SerializedName("jsLib") var jsLib: String? = null,
    @SerializedName("bookSourceIcon") var bookSourceIcon: String? = null,
    @SerializedName("exploreUrl") var exploreUrl: String? = null,
    @SerializedName("searchUrl") var searchUrl: String? = null,

    @SerializedName("ruleSearch") var ruleSearch: SearchRule = SearchRule(),
    @SerializedName("ruleExplore") var ruleExplore: ExploreRule = ExploreRule(),
    @SerializedName("ruleBookInfo") var ruleBookInfo: BookInfoRule = BookInfoRule(),
    @SerializedName("ruleToc") var ruleToc: TocRule = TocRule(),
    @SerializedName("ruleContent") var ruleContent: ContentRule = ContentRule(),
    @SerializedName("ruleReview") var ruleReview: ReviewRule? = null
) {
    /** 导入时保留的原始 JSON，用于「没编辑过的源原样导出」。 */
    @Transient
    var rawJson: String? = null

    /** 体检用的连续失败计数（本地状态，不导出）。 */
    @Transient
    var consecutiveFailures: Int = 0
}

data class SearchRule(
    @SerializedName("bookList") var bookList: String? = null,
    @SerializedName("name") var name: String? = null,
    @SerializedName("author") var author: String? = null,
    @SerializedName("intro") var intro: String? = null,
    @SerializedName("kind") var kind: String? = null,
    @SerializedName("lastChapter") var lastChapter: String? = null,
    @SerializedName("updateTime") var updateTime: String? = null,
    @SerializedName("bookUrl") var bookUrl: String? = null,
    @SerializedName("coverUrl") var coverUrl: String? = null,
    @SerializedName("wordCount") var wordCount: String? = null,
    @SerializedName("checkKeyWord") var checkKeyWord: String? = null
)

data class ExploreRule(
    @SerializedName("bookList") var bookList: String? = null,
    @SerializedName("name") var name: String? = null,
    @SerializedName("author") var author: String? = null,
    @SerializedName("intro") var intro: String? = null,
    @SerializedName("kind") var kind: String? = null,
    @SerializedName("lastChapter") var lastChapter: String? = null,
    @SerializedName("updateTime") var updateTime: String? = null,
    @SerializedName("bookUrl") var bookUrl: String? = null,
    @SerializedName("coverUrl") var coverUrl: String? = null,
    @SerializedName("wordCount") var wordCount: String? = null
)

data class BookInfoRule(
    @SerializedName("init") var init: String? = null,
    @SerializedName("name") var name: String? = null,
    @SerializedName("author") var author: String? = null,
    @SerializedName("intro") var intro: String? = null,
    @SerializedName("kind") var kind: String? = null,
    @SerializedName("lastChapter") var lastChapter: String? = null,
    @SerializedName("updateTime") var updateTime: String? = null,
    @SerializedName("coverUrl") var coverUrl: String? = null,
    @SerializedName("tocUrl") var tocUrl: String? = null,
    @SerializedName("wordCount") var wordCount: String? = null,
    @SerializedName("canReName") var canReName: String? = null,
    @SerializedName("downloadUrls") var downloadUrls: String? = null
)

data class TocRule(
    @SerializedName("chapterList") var chapterList: String? = null,
    @SerializedName("chapterName") var chapterName: String? = null,
    @SerializedName("chapterUrl") var chapterUrl: String? = null,
    @SerializedName("formatJs") var formatJs: String? = null,
    @SerializedName("isVolume") var isVolume: String? = null,
    @SerializedName("nextTocUrl") var nextTocUrl: String? = null,
    @SerializedName("updateTime") var updateTime: String? = null
)

data class ContentRule(
    @SerializedName("content") var content: String? = null,
    @SerializedName("title") var title: String? = null,
    @SerializedName("nextContentUrl") var nextContentUrl: String? = null,
    @SerializedName("webJs") var webJs: String? = null,
    @SerializedName("sourceRegex") var sourceRegex: String? = null,
    @SerializedName("replaceRegex") var replaceRegex: String? = null,
    @SerializedName("imageStyle") var imageStyle: String? = null,
    @SerializedName("payAction") var payAction: String? = null
)

data class ReviewRule(
    @SerializedName("reviewUrl") var reviewUrl: String? = null,
    @SerializedName("avatarRule") var avatarRule: String? = null,
    @SerializedName("contentRule") var contentRule: String? = null,
    @SerializedName("nameRule") var nameRule: String? = null,
    @SerializedName("timeRule") var timeRule: String? = null,
    @SerializedName("postUrl") var postUrl: String? = null,
    @SerializedName("reviewList") var reviewList: String? = null
)
