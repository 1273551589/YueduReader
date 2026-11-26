package com.ayanami.reader.core.pixiv

/**
 * Pixiv 图片地址处理：防盗链 Referer + 图床镜像重写。
 *
 * ### 为什么必须要 Referer
 *
 * `i.pximg.net` 是 Pixiv 的图床，它检查 `Referer` 必须是 `pixiv.net`。
 * 不带就是 **403**（实测确认：不带 → 403，带 → 200/404）。
 * 所以所有图床请求都得注入这个头，拦截器是唯一干净的做法 ——
 * 见 [PixivClient] 里的 `imageInterceptor`。
 *
 * ### 为什么需要镜像重写
 *
 * 国内网络直连 `i.pximg.net` 常年不稳（经常 TLS 重置或超时）。
 * 换一个反代域名就能读 —— 代价是反代可能慢、可能挂，
 * 所以做成设置项，默认直连，用户自己切。
 *
 * ### 重写规则
 *
 * 只换 **host**，path 原样保留。因为 path 里带着 `img-master` /
 * `c/480x960/...` 这类尺寸与目录信息，改了就 404。
 *
 * ```
 * https://i.pximg.net/c/480x960/img-master/img/2026/...jpg
 *   → https://i.pixiv.re/c/480x960/img-master/img/2026/...jpg
 * ```
 */
object PixivImageUrls {

    /** 官方图床域名。 */
    const val OFFICIAL_HOST = "i.pximg.net"

    /**
     * 已知可用的镜像。
     *
     * ⚠️ 镜像会失效，所以 UI 上要允许手填。
     * 实测 `i.pixiv.re` 现在对任意 path 返回 404（服务降级），
     * 但保留它作为可选项 —— 恢复了就能用，不需要改代码。
     */
    val KNOWN_MIRRORS = listOf(
        OFFICIAL_HOST,
        "i.pixiv.re",
        "i.pixiv.cat"
    )

    /**
     * 把任意 pixiv 图床地址重写成指定镜像。
     *
     * @param url    原始地址（可能已经是镜像域名）
     * @param mirror 目标 host；为空或等于官方时原样返回
     */
    fun rewrite(url: String, mirror: String?): String {
        if (url.isBlank()) return url
        // ⚠️ 只处理 Pixiv 图床。第一版漏了这个判断，
        // 结果任何 http(s) 地址都被换成镜像域名 —— 比如书源封面、
        // 站点图标全被改到 i.pixiv.re 下，必然 404。
        if (!isPixivImage(url)) return url

        val target = mirror?.trim()?.removePrefix("https://")?.removePrefix("http://")
            ?.trimEnd('/')
        if (target.isNullOrBlank() || target == OFFICIAL_HOST) {
            // 仍然要把「已经是别的镜像」的地址还原成官方，
            // 否则用户切回直连后还指着旧镜像
            return toOfficialHost(url)
        }
        return replaceHost(url, target)
    }

    /** 把任何已知镜像的地址还原成官方域名。 */
    fun toOfficialHost(url: String): String {
        var out = url
        for (h in KNOWN_MIRRORS) {
            if (h == OFFICIAL_HOST) continue
            out = replaceHost(out, OFFICIAL_HOST, from = h)
        }
        return out
    }

    /**
     * 把 host 换成 [to]。
     *
     * 用字符串定位 `://` 后的第一段，不做 URI 解析 ——
     * `java.net.URI` 对某些带特殊字符的 path 会抛异常，
     * 而这里只需要替换 authority，正则足够且更稳。
     */
    private fun replaceHost(url: String, to: String, from: String? = null): String {
        val m = Regex("""^(https?)://([^/]+)(.*)$""").find(url) ?: return url
        val scheme = m.groupValues[1]
        val host = m.groupValues[2]
        val rest = m.groupValues[3]
        if (from != null && !host.equals(from, ignoreCase = true)) return url
        if (host.equals(to, ignoreCase = true)) return url
        return "$scheme://$to$rest"
    }

    /** 这个地址是不是 Pixiv 图床（含所有已知镜像）。 */
    fun isPixivImage(url: String): Boolean {
        val host = runCatching {
            Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1)
        }.getOrNull() ?: return false
        val h = host.substringBefore(':')
        return KNOWN_MIRRORS.any { it.equals(h, ignoreCase = true) } ||
            h.endsWith(".pximg.net", ignoreCase = true)
    }

    /**
     * 请求该地址时应该带的 Referer。
     *
     * 图床要 pixiv.net，其他（比如 API）要具体页面。
     */
    fun refererFor(url: String): String =
        if (isPixivImage(url)) "https://www.pixiv.net/" else "https://www.pixiv.net/"
}
