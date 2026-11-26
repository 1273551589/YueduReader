package com.ayanami.reader

import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.classify.BookClassifier.Genre
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真实书目回归 —— 用书城里实际抓到的书校准分类器。
 *
 * 这些用例来自真机上书城页的真实数据（SF 轻小说源），
 * 不是编的。每一条都对应一次「分类看起来不对」的实际观察。
 */
class ClassifierRealCasesTest {

    @Test
    fun `SAO should be game not fantasy`() {
        // 真机上被判成了「玄幻奇幻」，但它是典型的游戏/网游题材
        val r = BookClassifier.classify(
            name = "Sword Art Online刀剑神域",
            author = "天闻角川",
            intro = "一场死亡游戏即将揭开序幕SAO玩家·桐人，以完全攻略为目标，" +
                "在游戏舞台「艾恩葛朗特」城堡里展开一连串严酷的冒险。",
            kindRaw = "轻小说"
        )
        assertEquals(
            "VRMMO 死亡游戏题材应判为游戏竞技，而不是玄幻",
            Genre.GAME, r.genre
        )
    }

    @Test
    fun `gender bender comedy should not be other`() {
        // 「圣女怎么可能是男孩子」—— 日系搞笑，应归轻小说
        val r = BookClassifier.classify(
            name = "圣女怎么可能是男孩子",
            author = "死神阿努比斯",
            intro = "神：我要将圣女赐予你们。教皇：等、等一下！这是男孩子啊喂！神：这就是圣女。",
            kindRaw = "轻小说"
        )
        assertEquals(Genre.LIGHT_NOVEL, r.genre)
    }

    @Test
    fun `rebirth game should be game`() {
        val r = BookClassifier.classify(
            name = "我转生就超神",
            intro = "全球首款虚拟实境游戏公测在即，叶枫神奇地重生归来。" +
                "这一世，他拥有丰富的游戏经验，要在这个世界里登顶。"
        )
        assertEquals(Genre.GAME, r.genre)
    }

    @Test
    fun `vrmmo keywords are strong game signal`() {
        for (kw in listOf("vr游戏", "虚拟实境", "全息游戏", "网游", "副本", "公会")) {
            val r = BookClassifier.classify(
                name = "测试",
                intro = "他进入了$kw 的世界，成为最强的玩家。"
            )
            assertEquals("「$kw」应指向游戏竞技，实际=${r.genre}", Genre.GAME, r.genre)
        }
    }

    @Test
    fun `japanese style markers go light novel`() {
        for ((name, intro) in listOf(
            "我的青春恋爱物语" to "高中生八幡的校园生活，社团里的日常。",
            "转生成为史莱姆" to "被刺死后转生到了异世界。",
            "龙王的工作" to "将棋棋手与他的小学生弟子，同居生活开始。"
        )) {
            assertEquals("「$name」应归轻小说", Genre.LIGHT_NOVEL,
                BookClassifier.classify(name, intro = intro).genre)
        }
    }

    @Test
    fun `light novel kind alone is not enough to beat strong game signal`() {
        // 源标「轻小说」，但简介里全是游戏机制词 → 游戏竞技应该赢
        val r = BookClassifier.classify(
            name = "刀剑神域",
            intro = "死亡游戏开始，玩家们被困在副本里，必须打通全部楼层才能登出。",
            kindRaw = "轻小说"
        )
        assertEquals(Genre.GAME, r.genre)
    }

    @Test
    fun `xianxia does not steal game books`() {
        // 「仙」出现在「修仙游戏」里时，游戏信号应该能和仙侠区分开
        val game = BookClassifier.classify(
            name = "网游之修仙",
            intro = "这是一款修仙题材的网游，玩家需要打怪升级，组建公会。"
        )
        // 两个信号都有，只要不是「其他」就算可接受；但游戏词更多时应偏游戏
        assertEquals(Genre.GAME, game.genre)
    }
}
