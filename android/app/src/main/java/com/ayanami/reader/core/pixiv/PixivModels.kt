package com.ayanami.reader.core.pixiv

/**
 * Pixiv 端口的数据模型。
 *
 * ### 为什么全部字段都有默认值
 *
 * Pixiv 的 JSON 字段**随版本漂移**（同一个 key 有时是字符串有时是对象），
 * 而且我们同时要兼容公共 API 镜像。Gson 遇到缺字段会塞 null 到非空声明里，
 * 所以这里一律给默认值 —— 少了字段是正常的，不该崩。
 *
 * ### `xRestrict` 是 R18 的唯一可信判据
 *
 * Pixiv 自己用 `xRestrict` 标记成人向：`0` 全年龄、`1` R18、`2` R18+。
 * **不要**用 tag 关键词猜 —— 那会把「健全」标签的图误判成成人向，
 * 也会漏掉没打 R18 tag 但设了 xRestrict 的作品。
 */

/** 一个作品（插画 / 漫画）的元数据。 */
data class PixivIllust(
    val id: String = "",
    val title: String = "",
    val authorName: String = "",
    val authorId: String = "",
    /** 作者头像（小图）。 */
    val authorAvatar: String = "",
    /** 封面/首图，已做过图床域名重写。 */
    val coverUrl: String = "",
    /** 所有页的原图地址，已做过图床域名重写。 */
    val pageUrls: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 已翻译成中文的 tag（Pixiv 会返回，可能为空）。 */
    val translatedTags: Map<String, String> = emptyMap(),
    val bookmarkCount: Int = 0,
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    val pageCount: Int = 1,
    /** Pixiv 的成人向标记：0 全年龄 / 1 R18 / 2 R18+。 */
    val xRestrict: Int = 0,
    val isManga: Boolean = false,
    /** 榜单排名（仅榜单接口有）。 */
    val rank: Int = 0,
    val createDate: String = "",
    /** 作品详情页地址（网页版，用于「在浏览器打开」）。 */
    val webUrl: String = ""
) {
    val isAdult: Boolean get() = xRestrict > 0

    /** 首个可用图片地址。 */
    val thumb: String get() = coverUrl.ifBlank { pageUrls.firstOrNull().orEmpty() }
}

/** 一个小说作品的元数据。 */
data class PixivNovel(
    val id: String = "",
    val title: String = "",
    /** 系列名（章节所属的长篇名），单篇时为空。 */
    val seriesTitle: String = "",
    val authorName: String = "",
    val authorId: String = "",
    val authorAvatar: String = "",
    val coverUrl: String = "",
    val tags: List<String> = emptyList(),
    val translatedTags: Map<String, String> = emptyMap(),
    val bookmarkCount: Int = 0,
    val likeCount: Int = 0,
    val viewCount: Int = 0,
    /** 字数（Pixiv 的 characterCount 与 wordCount 不总是相等，取较大的）。 */
    val wordCount: Int = 0,
    val xRestrict: Int = 0,
    val rank: Int = 0,
    val createDate: String = "",
    val description: String = "",
    val webUrl: String = ""
) {
    val isAdult: Boolean get() = xRestrict > 0
}

/** 一个画师。 */
data class PixivUser(
    val id: String = "",
    val name: String = "",
    val avatar: String = "",
    /** 代表作（拼贴卡片用的前 3 张）。 */
    val works: List<String> = emptyList(),
    val comment: String = ""
)

/**
 * 排行榜周期。
 *
 * `r18` 前缀的几项在 R18 总开关关闭时**不出现**，
 * 见 [PixivClient.availableModes]。
 */
enum class PixivRankMode(val param: String, val label: String, val adult: Boolean) {
    DAILY("daily", "日榜", false),
    WEEKLY("weekly", "周榜", false),
    MONTHLY("monthly", "月榜", false),
    ROOKIE("rookie", "新人榜", false),
    ORIGINAL("original", "原创榜", false),
    MALE("male", "男性向", false),
    FEMALE("female", "女性向", false),
    R18_DAILY("daily_r18", "R18 日", true),
    R18_WEEKLY("weekly_r18", "R18 周", true)
}

/**
 * 排行榜内容类型。
 *
 * 注意：插画榜用 `content=illust`，小说榜走的是另一个页面
 * （`/novel/ranking.php`），见 [PixivClient]。
 */
enum class PixivContentType(val param: String, val label: String) {
    ILLUST("illust", "插画"),
    MANGA("manga", "漫画"),
    NOVEL("novel", "小说")
}

/**
 * 一次 Pixiv 请求失败的原因。
 *
 * ⚠️ 必须分成这几类，因为**处置方式完全不同**：
 * - [NeedsLogin]：让用户去填 Cookie，重试没用
 * - [NeedsProxy]：网络被阻断，让用户配代理或换镜像
 * - [RateLimited]：Pixiv 嫌太快 —— 退避后重试有效，这也是
 *   「不许并发」的直接证据（实测 4 并发 → SSL EOF）
 * - [NotFound]：作品被删或 ID 错
 * - [Parse]：接口能通但结构变了 —— 这才是真 bug
 */
sealed class PixivError(val humanMessage: String) {
    data class NeedsLogin(val detail: String = "") :
        PixivError("这个内容需要登录 Pixiv。去「设置 → Pixiv」填 PHPSESSID。")

    data class NeedsProxy(val detail: String) :
        PixivError("连不上 Pixiv（$detail）。可能需要配置代理，或在设置里换 API 镜像。")

    data class RateLimited(val retryAfterMs: Long = 2000) :
        PixivError("请求被 Pixiv 限流了，稍等再试。")

    data class NotFound(val what: String) : PixivError("找不到 $what，可能已被删除。")

    data class Parse(val detail: String) : PixivError("Pixiv 返回的数据解析失败：$detail")

    data class Http(val code: Int) : PixivError("Pixiv 返回 HTTP $code。")
}
