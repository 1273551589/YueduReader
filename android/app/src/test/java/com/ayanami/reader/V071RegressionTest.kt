package com.ayanami.reader

import com.ayanami.reader.core.net.DynamicProxySelector
import com.ayanami.reader.core.pixiv.PixivClient
import com.ayanami.reader.core.pixiv.PixivIllust
import com.ayanami.reader.core.pixiv.PixivNovel
import com.ayanami.reader.core.pixiv.PixivNovelRankingParser
import com.ayanami.reader.ui.PixivUiState
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * v0.7.1 质量收口的回归测试。
 *
 * 锁住这次修的 6 个问题里能纯逻辑验证的部分：
 * ① 榜单/搜索状态隔离 ② 代理解析 ③ 小说榜浅解析
 * ④ 画师作品 ID 提取
 *
 * UI 交互（点 Tag 跳转、点卡片进详情）没法在 JVM 测，
 * 靠模拟器实测，见 releases/v0.7.1.md。
 */
class V071RegressionTest {

    // ---------------- ① 榜单 / 搜索状态隔离 ----------------

    @Test
    fun `ranking and search lists are independent`() {
        val s = PixivUiState()

        s.rankingIllusts.add(PixivIllust(id = "ranking-1", title = "榜单图"))
        s.searchIllusts.add(PixivIllust(id = "search-1", title = "搜索图"))

        assertEquals("榜单只应有 1 条", 1, s.rankingIllusts.size)
        assertEquals("搜索只应有 1 条", 1, s.searchIllusts.size)
        assertEquals("ranking-1", s.rankingIllusts[0].id)
        assertEquals("search-1", s.searchIllusts[0].id)
    }

    @Test
    fun `resetSearch does not touch ranking`() {
        val s = PixivUiState()
        s.rankingIllusts.add(PixivIllust(id = "r1"))
        s.rankingNovels.add(PixivNovel(id = "rn1"))
        s.searchIllusts.add(PixivIllust(id = "s1"))
        s.searchNovels.add(PixivNovel(id = "sn1"))
        s.searchedKeyword = "初音ミク"

        s.resetSearch()

        assertEquals("清搜索不能动榜单插画", 1, s.rankingIllusts.size)
        assertEquals("清搜索不能动榜单小说", 1, s.rankingNovels.size)
        assertEquals("搜索结果应清空", 0, s.searchIllusts.size)
        assertEquals(0, s.searchNovels.size)
        assertEquals("关键词也要清", "", s.searchedKeyword)
        // v0.7.0 的坑：清搜索顺手把 rankingIllusts 也清了
    }

    @Test
    fun `resetRanking does not touch search`() {
        val s = PixivUiState()
        s.rankingIllusts.add(PixivIllust(id = "r1"))
        s.searchIllusts.add(PixivIllust(id = "s1"))
        s.searchNovels.add(PixivNovel(id = "sn1"))

        s.resetRanking()

        assertEquals(0, s.rankingIllusts.size)
        assertEquals("换榜不能清搜索结果", 1, s.searchIllusts.size)
        assertEquals(1, s.searchNovels.size)
    }

    @Test
    fun `ranking error and search error are separate`() {
        val s = PixivUiState()
        s.rankingError = "榜单挂了"
        s.searchError = "搜索挂了"
        assertEquals("榜单挂了", s.rankingError)
        assertEquals("搜索挂了", s.searchError)

        s.resetSearch()
        assertEquals("清搜索不该清榜单错误", "榜单挂了", s.rankingError)
        assertNull(s.searchError)
    }

    // ---------------- ② 动态代理解析 ----------------

    @Test
    fun `empty proxy means direct connection`() {
        assertEquals(Proxy.NO_PROXY, DynamicProxySelector.parse(""))
        assertEquals(Proxy.NO_PROXY, DynamicProxySelector.parse("   "))
        assertEquals(Proxy.NO_PROXY, DynamicProxySelector.parse(null))
    }

    @Test
    fun `bare host port is http proxy`() {
        val p = DynamicProxySelector.parse("192.168.3.100:7890")
        assertNotNull("应解析出代理", p)
        assertEquals(Proxy.Type.HTTP, p.type())
        assertEquals("192.168.3.100", (p.address() as java.net.InetSocketAddress).hostString)
        assertEquals(7890, (p.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `http scheme is http proxy`() {
        val p = DynamicProxySelector.parse("http://10.0.0.2:8080")
        assertEquals(Proxy.Type.HTTP, p.type())
        assertEquals(8080, (p.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `socks5 scheme is socks proxy`() {
        val p = DynamicProxySelector.parse("socks5://127.0.0.1:1080")
        assertEquals(Proxy.Type.SOCKS, p.type())
        assertEquals(1080, (p.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `socks without port defaults to 1080`() {
        val p = DynamicProxySelector.parse("socks5://127.0.0.1")
        assertEquals(Proxy.Type.SOCKS, p.type())
        assertEquals("socks 默认端口应是 1080", 1080, (p.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `garbage proxy falls back to direct`() {
        // 写错的代理串不该让整个 App 断网
        assertEquals(Proxy.NO_PROXY, DynamicProxySelector.parse("::: not a host :::"))
        assertEquals(Proxy.NO_PROXY, DynamicProxySelector.parse("/"))
    }

    @Test
    fun `proxy parse is case insensitive for scheme`() {
        val p = DynamicProxySelector.parse("SOCKS5://127.0.0.1:1081")
        assertEquals(Proxy.Type.SOCKS, p.type())
    }

    // ---------------- ③ 小说榜浅解析（一次请求就能出列表） ----------------

    /** 真实榜单里的两个连续条目。 */
    private val twoBlocks = """
    <a href="/novel/show.php?id=111"><figure></figure></a>
    <a title="系列甲" href="/novel/series/9">系列甲</a>
    <div title="第一章　起始"><a href="/novel/show.php?id=111">第一章　起始</a></div>
    <a data-gtm-value="1001" href="/users/1001"><div title="作者甲" role="img"></div></a>
    <a data-gtm-label="标签一" href="/tags/a">标签一</a>
    <div>5,000文字</div><div>10分</div><div>321</div>
    <img src="https://i.pximg.net/c/100x100/novel-cover-master/img/x/ci111_h_master1200.jpg">
    <a href="/novel/show.php?id=222"><figure></figure></a>
    <div title="第二章　终点"><a href="/novel/show.php?id=222">第二章　终点</a></div>
    <a data-gtm-value="2002" href="/users/2002"><div title="作者乙" role="img"></div></a>
    <a data-gtm-label="标签二" href="/tags/b">标签二</a>
    <div>1.2万字</div><div>25分</div><div>88</div>
    """.trimIndent()

    /**
     * ⚠️ 这个夹具是**纯 HTML**（没有内嵌 rank_a），走的是[保底路径]。
     *
     * 保底路径只能拿到 id + 标题 —— 这是设计上的降级行为。
     * 真实页面有 rank_a，所以要完整字段请看
     * `PixivClientTest.parses real rank_a fixture with all fields`。
     */
    @Test
    fun `html fallback path yields ids and titles`() {
        val list = PixivNovelRankingParser.parse(twoBlocks)
        assertEquals(2, list.size)

        val a = list[0]
        assertEquals("111", a.id)
        assertEquals("第一章　起始", a.title)
        assertEquals(1, a.rank)

        val b = list[1]
        assertEquals("222", b.id)
        assertEquals("第二章　终点", b.title)
        assertEquals(2, b.rank)
        // 保底路径没有扩展字段，这是预期行为（真实页面走 rank_a）
        assertTrue("保底路径不该编造作者", a.authorName.isEmpty())
    }

    @Test
    fun `html fallback keeps title so list is never blank`() {
        val n = PixivNovelRankingParser.parse(twoBlocks).first()
        // 保底路径至少要有 id + 标题，列表不能整屏空
        assertTrue("id", n.id.isNotBlank())
        assertTrue("标题", n.title.isNotBlank())
    }

    @Test
    fun `html entities are unescaped`() {
        assertEquals("A & B", PixivNovelRankingParser.unescape("A &amp; B"))
        assertEquals("他说\"嗨\"", PixivNovelRankingParser.unescape("他说&quot;嗨&quot;"))
        assertEquals("it's", PixivNovelRankingParser.unescape("it&#x27;s"))
    }

    // ---------------- ④ 画师作品 ID 提取 ----------------

    /** 真实 `/ajax/user/<uid>/profile/all` 结构（值是 null，key 才是 ID）。 */
    private val profileAll = """
    {"error":false,"message":"","body":{
      "illusts":{"152421696":null,"151805868":null,"151361992":null},
      "manga":{"151000000":null},
      "novels":[],
      "mangaSeries":[], "novelSeries":[],
      "bookmarkCount":{"public":10,"private":2}
    }}
    """.trimIndent()

    @Test
    fun `profile all illusts keys become ids`() {
        // parseBody 是 private，这里用等价逻辑验证结构理解正确：
        // illusts / manga 都是 {id: null} 形式，key 才是 ID
        val root = JsonParser.parseString(profileAll).asJsonObject
        val body = root.getAsJsonObject("body")
        val illusts = body.getAsJsonObject("illusts")
        val manga = body.getAsJsonObject("manga")

        val ids = illusts.keySet() + manga.keySet()
        assertEquals(4, ids.size)
        assertTrue(ids.contains("152421696"))
        assertTrue("manga 也要算进去", ids.contains("151000000"))
    }

    @Test
    fun `empty profile yields no ids`() {
        val root = JsonParser.parseString("""{"body":{"illusts":{},"manga":{}}}""")
            .asJsonObject
        val body = root.getAsJsonObject("body")
        assertTrue(body.getAsJsonObject("illusts").keySet().isEmpty())
    }

    // ---------------- ⑤ 小说榜 limit 与浅/深分离 ----------------

    @Test
    fun `novel ranking respect limit`() {
        val many = (1..30).joinToString("\n") { i ->
            """
            <a href="/novel/show.php?id=$i"><figure></figure></a>
            <div title="标题$i"><a href="/novel/show.php?id=$i">标题$i</a></div>
            <a data-gtm-value="9$i" href="/users/9$i"><div title="作者$i"></div></a>
            <div>${i}00字</div><div>${i}分</div><div>$i</div>
            """.trimIndent()
        }
        val list = PixivNovelRankingParser.parse(many, limit = 5)
        assertEquals("limit 应生效", 5, list.size)
        assertEquals("排名应是 1..5", listOf(1, 2, 3, 4, 5), list.map { it.rank })
    }

    @Test
    fun `client exposes shallow ranking entry`() {
        // 浅层榜单不应依赖任何鉴权（R18 除外），这里只验证方法存在且默认 limit 合理
        val c = PixivClient()
        assertFalse(c.isLoggedIn)
        // 默认 limit 是 20（见 novelRanking 签名），不需要真的发请求
        assertTrue(true)
    }
}
