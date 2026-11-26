package com.ayanami.reader

import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.classify.BookClassifier.AdultTag
import com.ayanami.reader.core.classify.BookClassifier.Genre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地分类引擎的契约测试。
 *
 * 重点防两类事故：
 * 1. **R18 漏标** —— 未开 R18 时成人内容泄漏到普通列表，这是最严重的产品事故
 * 2. **分类乱标** —— 书源给个"玄幻"就当玄幻，实际是仙侠
 */
class BookClassifierTest {

    @Test
    fun `xianxia keywords beat source kind`() {
        // 书源标"玄幻"，但书名含"仙"、简介全是修真词 → 应该判仙侠
        val r = BookClassifier.classify(
            name = "仙逆",
            intro = "王林踏入修真之路，历经炼气、金丹、元婴，终渡劫飞升。",
            kindRaw = "玄幻,热血"
        )
        assertEquals(Genre.WUXIA, r.genre)
    }

    @Test
    fun `scifi keywords`() {
        val r = BookClassifier.classify(
            name = "星舰指挥官",
            intro = "在遥远的星系，一支舰队穿越虫洞，与外星文明的殖民地展开战争。"
        )
        assertEquals(Genre.SCIFI, r.genre)
    }

    @Test
    fun `mecha and apocalypse both scifi`() {
        assertEquals(
            Genre.SCIFI,
            BookClassifier.classify("机甲战神", intro = "末日降临，废土之上，人类驾驶机甲与丧尸战斗。").genre
        )
    }

    @Test
    fun `urban romance`() {
        val r = BookClassifier.classify(
            name = "总裁的替身新娘",
            intro = "她是公司里最不起眼的实习生，却在相亲宴上被集团总裁当众点名结婚。"
        )
        assertEquals(Genre.URBAN, r.genre)
    }

    @Test
    fun `mystery`() {
        val r = BookClassifier.classify(
            name = "第十三层凶宅",
            intro = "凶案现场的死者手里攥着一枚铜钱，警察发现所有线索都指向那个被诅咒的老宅。"
        )
        assertEquals(Genre.MYSTERY, r.genre)
    }

    @Test
    fun `history`() {
        val r = BookClassifier.classify(
            name = "铁血大明",
            intro = "穿越成为大明皇子，面对朝廷倾轧与敌国边境的战争，他选择变法强军。"
        )
        assertEquals(Genre.HISTORY, r.genre)
    }

    @Test
    fun `light novel`() {
        val r = BookClassifier.classify(
            name = "转生成为史莱姆",
            intro = "高中生意外死亡后转生到异世界，成了最弱的史莱姆，却拥有吞噬一切的能力。"
        )
        assertEquals(Genre.LIGHT_NOVEL, r.genre)
    }

    @Test
    fun `game`() {
        val r = BookClassifier.classify(
            name = "全职业玩家",
            intro = "游戏开服第一天，他觉醒了唯一隐藏职业，从此单人通关所有副本，排行第一。"
        )
        assertEquals(Genre.GAME, r.genre)
    }

    @Test
    fun `no signal falls to other`() {
        val r = BookClassifier.classify(name = "某某", intro = null, kindRaw = null)
        assertEquals(Genre.OTHER, r.genre)
    }

    // ---------------- R18 判定（最关键） ----------------

    @Test
    fun `mother son detected`() {
        val r = BookClassifier.classify(
            name = "我的母亲",
            intro = "自从父亲去世后，母亲一个人把我拉扯大……"
        )
        assertTrue("必须标记为成人向", r.isAdult)
        assertTrue("应命中母子细分", r.adultTags.contains(AdultTag.MOTHER_SON))
    }

    @Test
    fun `ntr detected`() {
        val r = BookClassifier.classify(
            name = "隔壁的妻子",
            intro = "他发现自己的妻子最近总是很晚回家，直到那天他撞见了那一幕——出轨。"
        )
        assertTrue(r.isAdult)
        assertTrue("应识别 NTR", r.adultTags.contains(AdultTag.NTR))
    }

    @Test
    fun `bdsm and tentacle`() {
        val r = BookClassifier.classify(
            name = "驯服",
            intro = "她被戴上了项圈，从此成为他的所有物，接受调教。"
        )
        assertTrue(r.isAdult)
        assertTrue(r.adultTags.contains(AdultTag.BDSM))
    }

    @Test
    fun `yuri detected`() {
        val r = BookClassifier.classify("百合花开", intro = "两个女孩之间的禁忌之恋，女同的故事。")
        assertTrue(r.isAdult)
        assertTrue(r.adultTags.contains(AdultTag.YURI))
    }

    @Test
    fun `adult source group forces adult even without keywords`() {
        // 书名简介完全干净，但书源分组是 R18 → 也必须标成人
        val r = BookClassifier.classify(
            name = "春天的故事",
            intro = "一个关于春天的温暖故事，讲述小镇上的人们。",
            sourceGroup = "R18"
        )
        assertTrue("R18 分组的源必须整源标成人", r.isAdult)
    }

    @Test
    fun `adult kind marker also forces adult`() {
        val r = BookClassifier.classify(
            name = "无名",
            intro = null,
            kindRaw = "18禁,连载"
        )
        assertTrue(r.isAdult)
    }

    @Test
    fun `clean book is not adult`() {
        val r = BookClassifier.classify(
            name = "三体",
            intro = "文化大革命如火如荼进行的同时，军方探寻外星文明的绝秘计划取得了突破性进展。",
            kindRaw = "科幻"
        )
        assertTrue("干净的书不能被误判成人", !r.isAdult)
        assertEquals(Genre.SCIFI, r.genre)
    }

    @Test
    fun `adult without strong genre goes to adult category`() {
        // 命中成人词库，但书名/简介里没有任何「玄幻/都市/科幻」分类信号
        val r = BookClassifier.classify(
            name = "邻家的她",
            intro = "她敲开了我的门，说今晚丈夫不在家，然后扑进了我怀里，我们开始偷情。"
        )
        assertTrue(r.isAdult)
        // 没有明确的其他分类信号 → 主分类归成人向
        assertEquals(Genre.ADULT, r.genre)
    }

    // ---------------- 标签 ----------------

    @Test
    fun `system tag extracted`() {
        val r = BookClassifier.classify(
            name = "签到系统",
            intro = "他获得了一个签到系统，每天签到就能变强。"
        )
        assertTrue("应打上系统标签：${r.tags}", r.tags.contains("系统"))
    }

    @Test
    fun `kind tags preserved but truncated`() {
        val r = BookClassifier.classify(
            name = "测试",
            kindRaw = "玄幻,热血,连载中,升级流,爽文,单女主"
        )
        assertTrue("应保留部分 kind 标签：${r.tags}", r.tags.isNotEmpty())
        assertTrue("标签不应超过 4 个：${r.tags}", r.tags.size <= 4)
    }

    @Test
    fun `confidence is zero without signal`() {
        assertEquals(0f, BookClassifier.classify(name = "zzz").confidence, 0.001f)
    }

    @Test
    fun `confidence higher when signals agree`() {
        val weak = BookClassifier.classify(name = "随便", intro = null, kindRaw = "科幻")
        val strong = BookClassifier.classify(
            name = "星际机甲",
            intro = "舰队穿越星系，人工智能掌控了殖民地。",
            kindRaw = "科幻"
        )
        assertTrue(
            "多信号一致时置信度应更高：weak=${weak.confidence} strong=${strong.confidence}",
            strong.confidence > weak.confidence
        )
    }

    // ---------------- 性能（书城要跑上千本） ----------------

    @Test
    fun `classify is fast enough for bulk`() {
        val intro = "他踏入修真界，历经炼气、金丹、元婴，一路飞升成为仙人。" .repeat(6)
        val start = System.nanoTime()
        repeat(2000) {
            BookClassifier.classify("测试书名$it", intro = intro, kindRaw = "玄幻,热血")
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("2000 次分类应在 500ms 内，实际 ${ms}ms", ms < 500)
    }
}
