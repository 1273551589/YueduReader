package com.ayanami.reader

import com.ayanami.reader.core.pixiv.PixivClient
import com.ayanami.reader.core.pixiv.PixivContentType
import com.ayanami.reader.core.pixiv.PixivError
import com.ayanami.reader.core.pixiv.PixivNovelParser
import com.ayanami.reader.core.pixiv.PixivRankMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pixiv 联网验证。
 *
 * 默认跳过。开关：
 * ```
 * ./gradlew testDebugUnitTest --tests "*PixivOnlineTest" -Dpixiv.online=true
 * ```
 *
 * 这个测试存在的理由：解析层的单元测试用的是**抓下来的真实 JSON**，
 * 但如果 Pixiv 悄悄改了接口（或换了 host），单测仍然全绿而 App 全废。
 * 所以必须有一个真联网的哨兵。
 *
 * ⚠️ 全部串行且间隔拉长 —— 实测 Pixiv 对并发极其敏感，
 * 4 并发直接 TLS 断开。测试里也不许并发。
 */
class PixivOnlineTest {

    private val online get() = System.getProperty("pixiv.online") == "true"

    private fun client() = PixivClient().apply {
        minIntervalMs = 1200
        retries = 3
    }

    @Test
    fun `illust ranking returns real data without login`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()
        val list = c.illustRanking(PixivRankMode.DAILY, PixivContentType.ILLUST)

        println("=== 插画日榜：${list.size} 条 ===")
        list.take(5).forEach {
            println("  #${it.rank} ${it.title.take(24)} | ${it.authorName} | ♡${it.bookmarkCount} | ${it.coverUrl.take(60)}")
        }

        assertTrue("免登录应能拿到插画榜", list.isNotEmpty())
        assertTrue("条目必须有 ID", list.all { it.id.isNotBlank() })
        assertTrue("条目必须有标题", list.count { it.title.isNotBlank() } >= list.size / 2)
        assertTrue("封面必须是图床地址", list.all { it.coverUrl.contains("pximg") })
        assertTrue("至少有一条有收藏数", list.any { it.bookmarkCount > 0 })
        assertTrue("必须有排名", list.any { it.rank > 0 })
    }

    @Test
    fun `novel ranking resolves ids and details`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()

        val ids = c.novelRankingIds(PixivRankMode.DAILY, limit = 3)
        println("=== 小说榜 ID：$ids ===")
        assertTrue("应能从榜单页提取出 novel id", ids.isNotEmpty())
        assertTrue("ID 应是纯数字", ids.all { it.matches(Regex("""\d+""")) })

        val novels = c.novelRanking(PixivRankMode.DAILY, limit = 3)
        println("=== 小说榜详情：${novels.size} 条 ===")
        novels.forEach {
            println("  #${it.rank} ${it.title.take(24)} | ${it.authorName} | ${it.wordCount}字 ♡${it.bookmarkCount} | tags=${it.tags.take(3)}")
        }
        assertTrue("应至少解析出 1 条小说", novels.isNotEmpty())
        assertTrue("小说应有标题", novels.any { it.title.isNotBlank() })
        assertTrue("小说应有作者", novels.any { it.authorName.isNotBlank() })
        assertTrue("小说应有字数或收藏", novels.any { it.wordCount > 0 || it.bookmarkCount > 0 })
    }

    @Test
    fun `novel detail carries readable text`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()

        val ids = c.novelRankingIds(PixivRankMode.DAILY, limit = 2)
        assumeTrue("没有拿到小说 ID，跳过", ids.isNotEmpty())

        // 逐个试，取第一篇有正文的（排行榜里偶尔有被删/私密的作品）
        var got: String? = null
        for (id in ids) {
            val text = c.novelText(id)
            if (!text.isNullOrBlank()) { got = text; break }
        }
        assumeTrue("两篇都没拿到正文（可能是网络或登录限制）", got != null)

        val parsed = PixivNovelParser.parse(got!!)
        println("=== 小说正文 ===")
        println("  原始长度 ${got.length} → 纯文本 ${parsed.plainText.length}")
        println("  章节数 ${parsed.sections.size}，注音 ${parsed.rubyCount} 条，含分页=${parsed.hasNewPage}")
        println("  开头：${parsed.plainText.take(120).replace("\n", " ⏎ ")}")

        assertTrue("正文应有实际内容", parsed.plainText.length > 20)
        assertTrue("不应残留 newpage 标记", !parsed.plainText.contains("[newpage]"))
        assertTrue("不应残留 ruby 标记", !parsed.plainText.contains("[[rb:"))
        assertTrue("不应残留 jump 标记", !parsed.plainText.contains("[jump:"))
    }

    @Test
    fun `search works without login`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()
        val r = c.search("初音ミク")

        println("=== 搜索「初音ミク」===")
        println("  插画 ${r.illusts.size} 条，小说 ${r.novels.size} 条，相关 tag ${r.relatedTags.take(6)}")
        r.illusts.take(3).forEach {
            println("    ${it.title.take(24)} | ${it.authorName} | ♡${it.bookmarkCount}")
        }

        assertTrue("免登录应能搜到插画", r.illusts.isNotEmpty())
        assertTrue("搜索结果应有 ID", r.illusts.all { it.id.isNotBlank() })
        assertTrue("封面应是图床地址", r.illusts.all { it.coverUrl.contains("pximg") })
    }

    @Test
    fun `search filters adult when asked`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()
        val raw = c.search("オリジナル")
        val filtered = c.search("オリジナル", filterAdult = true)

        val rawAdult = raw.illusts.count { it.isAdult }
        val filteredAdult = filtered.illusts.count { it.isAdult }
        println("=== R18 过滤 === 原结果成人向 $rawAdult 条 → 过滤后 $filteredAdult 条")
        println("  原 ${raw.illusts.size} 条 → 过滤后 ${filtered.illusts.size} 条")

        assertTrue("过滤后不应有成人向条目", filteredAdult == 0)
    }

    @Test
    fun `r18 ranking without login gives clear error not silent empty`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = PixivClient()
        val list = c.illustRanking(PixivRankMode.R18_DAILY)
        println("=== R18 榜未登录 === 结果 ${list.size} 条，错误=${c.lastError}")
        assertTrue("未登录时 R18 榜应为空", list.isEmpty())
        assertTrue(
            "必须给出 NeedsLogin 而不是静默空列表",
            c.lastError is PixivError.NeedsLogin
        )
    }

    @Test
    fun `image fetches succeed with referer and fail without`() = runBlocking {
        assumeTrue("需要 -Dpixiv.online=true", online)
        val c = client()
        val list = c.illustRanking(PixivRankMode.DAILY, PixivContentType.ILLUST)
        assumeTrue("没拿到榜单，跳过", list.isNotEmpty())
        val url = list.first().coverUrl
        println("=== 图床验证 === $url")

        // 带 Referer（走 client 的拦截器）
        val withRef = fetch(c, url, withReferer = true)
        val withoutRef = fetch(c, url, withReferer = false)
        println("  带 Referer → HTTP $withRef")
        println("  不带 Referer → HTTP $withoutRef")

        assertTrue("带 Referer 应成功（200）", withRef in 200..299)
        // 这是防盗链的实证：不带必须是 403
        assertTrue("不带 Referer 必须被拒（403 或 404）", withoutRef == 403 || withoutRef == 404)
    }

    private fun fetch(c: PixivClient, url: String, withReferer: Boolean): Int {
        val b = okhttp3.OkHttpClient.Builder()
        if (withReferer) b.addInterceptor(c.imageInterceptor)
        return runCatching {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", PixivClient.UA)
                .build()
            b.build().newCall(req).execute().use { it.code }
        }.getOrDefault(-1)
    }
}
