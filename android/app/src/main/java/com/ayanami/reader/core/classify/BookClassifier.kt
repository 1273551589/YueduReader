package com.ayanami.reader.core.classify

/**
 * 本地图书分类 / 打标引擎 —— **纯函数，零网络，零数据库**。
 *
 * ### 为什么需要它
 *
 * 书源自带的 `kind` 是**不可信**的：
 * - 大量源的 `ruleSearch.kind` 是空规则 → 分类为 null
 * - 有值的也常是一长串站内标签（"玄幻,修真,热血,升级流,连载中"），
 *   塞不进书城的固定分类
 * - 同一个"科幻"在 A 站指星际机甲、在 B 站指末世丧尸
 *
 * 所以分类必须**自己算**：以书的「书名 + 简介 + 书源官方 kind」为输入，
 * 用本地关键词库加权投票，输出一个主分类 + 若干属性标签 + 分级判定。
 *
 * ### 设计约束
 *
 * 1. **纯本地**：不联网、不调模型。书城要并发预加载几十个源的上千本书，
 *    每次分类都必须是纳秒级 —— 所以是「一次扫描 + 查表」，不做正则回溯。
 * 2. **可扩展**：规则库是数据不是代码，后续可以放进 MOD 的 `categories` 类型。
 * 3. **R18 绝不漏**：命中成人词库或来自 R18 书源 → 强制 `isAdult = true`。
 *    未开 R18 时上层据此硬过滤，**不依赖 UI 自觉**。
 */
object BookClassifier {

    // ---------------- 输出 ----------------

    /** 书城的一级分类。`ALL` 只在 UI 用，不参与打标。 */
    enum class Genre(val label: String) {
        FANTASY("玄幻奇幻"),
        WUXIA("武侠仙侠"),
        URBAN("都市言情"),
        SCIFI("科幻末世"),
        MYSTERY("悬疑灵异"),
        HISTORY("历史军事"),
        LIGHT_NOVEL("轻小说"),
        GAME("游戏竞技"),
        ADULT("成人向"),
        OTHER("其他")
    }

    /** R18 细分（仅在开启 R18 后展示）。 */
    enum class AdultTag(val label: String) {
        MOTHER_SON("母子"),
        CAMPUS("校园纯爱"),
        NTR("NTR"),
        YURI("百合"),
        BDSM("调教"),
        MATURE_WOMAN("人妻熟女"),
        OTHER_ADULT("其他成人")
    }

    /**
     * 分类结果。
     *
     * @param genre      主分类（单一）
     * @param tags       属性标签（多个，用于卡片上的小标签）
     * @param adultTags  R18 细分（可能为空）
     * @param isAdult    是否成人向 —— **不可被上层忽略**
     * @param confidence 主分类置信度 0..1
     */
    data class Result(
        val genre: Genre,
        val tags: List<String>,
        val adultTags: List<AdultTag> = emptyList(),
        val isAdult: Boolean = false,
        val confidence: Float = 0f
    )

    // ---------------- 入口 ----------------

    /**
     * 给一本书打标。
     *
     * @param name     书名
     * @param author   作者（弱信号，仅用于少量特例）
     * @param intro    简介
     * @param kindRaw  书源自带的 kind 原串（逗号/空格分隔的站内标签）
     * @param sourceGroup 书源分组名（如 "R18"、"成人"）
     */
    fun classify(
        name: String,
        author: String = "",
        intro: String? = null,
        kindRaw: String? = null,
        sourceGroup: String? = null
    ): Result {
        // 简介可能很长，取头部 600 字足够 —— 关键词集中在开头
        val text = buildString {
            append(name)
            append('\u0000')
            if (author.isNotBlank()) append(author)
            append('\u0000')
            intro?.let { append(it.take(600)) }
        }

        // ---- 1) 成人判定（必须先做，决定后面是否泄漏）----
        val adultHits = matchAdult(text + "\u0000" + (kindRaw ?: ""))
        val fromAdultSource = isAdultSource(sourceGroup, kindRaw)
        val isAdult = adultHits.isNotEmpty() || fromAdultSource

        // ---- 2) 主分类投票 ----
        val scores = HashMap<Genre, Int>()
        fun vote(g: Genre, w: Int) {
            if (w <= 0) return
            scores[g] = (scores[g] ?: 0) + w
        }

        // 2a. 书源官方 kind：权重最低，只作修正（且不盲从）
        kindRaw?.let { raw ->
            for (token in raw.split(',', '，', ' ', '/', '|', '、')) {
                val t = token.trim()
                if (t.isEmpty()) continue
                genreOfToken(t)?.let { vote(it, KIND_WEIGHT) }
            }
        }

        // 2b. 书名：强信号（作者起名会刻意点明类型）
        for ((g, words) in TITLE_RULES) {
            for (w in words) {
                if (name.contains(w)) { vote(g, TITLE_WEIGHT); break }
            }
        }

        // 2c. 简介：中信号，可叠加多次（多个词命中说明真的很像）
        for ((g, words) in INTRO_RULES) {
            var hits = 0
            for (w in words) {
                if (text.contains(w)) hits++
                if (hits >= 3) break
            }
            if (hits > 0) vote(g, INTRO_WEIGHT * hits)
        }

        // ---- 3) 定主分类 ----
        val best = scores.maxByOrNull { it.value }
        val genre = when {
            // 完全没有分类信号：成人书进成人向，其它进「其他」
            best == null -> if (isAdult) Genre.ADULT else Genre.OTHER
            // 有分类信号但太弱，而书本身是成人向 → 归成人向
            isAdult && best.value < STRONG_THRESHOLD -> Genre.ADULT
            else -> best.key
        }

        /**
         * 置信度用**绝对强度**，不用「占比」。
         *
         * 占比有个致命缺陷：只有一种分类拿到票时占比恒为 1.0，
         * 哪怕那票只来自一个 kind 标签。用户看到「100% 确信是玄幻」
         * 而其实是瞎猜的，比不给置信度更糟。
         *
         * 绝对强度按 [SATURATION_WEIGHT] 饱和：加权分越高越确信。
         */
        val confidence = if (best == null) 0f
        else (best.value.toFloat() / SATURATION_WEIGHT).coerceAtMost(1f)

        // ---- 4) 属性标签 ----
        val tags = LinkedHashSet<String>()
        // 书源 kind 里的标签保留（截断到 3 个，避免卡片太挤）
        kindRaw?.split(',', '，', '/', '|', '、')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.length <= 6 }
            ?.take(3)
            ?.forEach { tags.add(it) }

        for ((label, words) in ATTRIBUTE_RULES) {
            if (words.any { name.contains(it) || text.contains(it) }) tags.add(label)
        }

        return Result(
            genre = genre,
            tags = tags.take(4),
            adultTags = adultHits,
            isAdult = isAdult,
            confidence = confidence
        )
    }

    // ---------------- 成人判定 ----------------

    /**
     * R18 判定。
     *
     * 这里的词库要**宁可错杀** —— 漏一本的代价是"用户没开 R18 也看到了"，
     * 那是最严重的产品事故。误判成人的代价只是少显示一本。
     */
    private fun matchAdult(text: String): List<AdultTag> {
        val out = LinkedHashSet<AdultTag>()
        for ((tag, words) in ADULT_RULES) {
            if (words.any { text.contains(it) }) out.add(tag)
        }
        return out.toList()
    }

    /** 书源分组 / kind 里带这些词的，整源视为 R18。 */
    private fun isAdultSource(group: String?, kindRaw: String?): Boolean {
        val probe = listOfNotNull(group, kindRaw).joinToString(",").lowercase()
        if (probe.isBlank()) return false
        return ADULT_SOURCE_MARKERS.any { probe.contains(it) }
    }

    private val ADULT_SOURCE_MARKERS = listOf(
        "r18", "r-18", "18禁", "成人", "色情", "情色", "h文", "肉文",
        "高h", "荤", "黄", "porn", "nsfw", "xxx"
    )

    // ---------------- 规则库 ----------------

    private const val KIND_WEIGHT = 2
    private const val TITLE_WEIGHT = 5
    private const val INTRO_WEIGHT = 2

    /** 低于这个加权分算「分类信号不足」，成人书会归到成人向。 */
    private const val STRONG_THRESHOLD = 5

    /** 加权分到达这个值就算「完全确信」（书名 + 多词简介 + kind 大概 12~15 分）。 */
    private const val SATURATION_WEIGHT = 15

    /** 书名关键词 → 分类。强信号。 */
    private val TITLE_RULES: Map<Genre, List<String>> = mapOf(
        Genre.FANTASY to listOf(
            "斗罗", "斗破", "神王", "龙帝", "魔帝", "天神", "神域", "异世",
            "诸天", "万界", "无上", "帝尊", "龙王", "圣尊", "大陆"
        ),
        Genre.WUXIA to listOf(
            "仙", "修真", "飞升", "剑道", "剑来", "武道", "江湖", "侠客",
            "修仙", "炼气", "金丹", "元婴", "渡劫", "问道", "长生", "蜀山"
        ),
        Genre.URBAN to listOf(
            "都市", "总裁", "校花", "兵王", "赘婿", "重生八零", "重回",
            "从校", "甜蜜", "恋", "婚", "娱乐圈", "豪门", "老婆", "前妻"
        ),
        Genre.SCIFI to listOf(
            "机甲", "星际", "末日", "末世", "赛博", "废土", "银河", "星舰",
            "算力", "文明", "三体", "殖民", "生化", "丧尸", "量子"
        ),
        Genre.MYSTERY to listOf(
            "诡", "悬疑", "灵异", "凶宅", "盗墓", "鬼", "阴间", "怪谈",
            "禁忌", "验尸", "刑侦", "推理", "谜案", "巫师"
        ),
        Genre.HISTORY to listOf(
            "三国", "大明", "大唐", "大宋", "铁血", "军阀", "war", "战场",
            "汉", "王朝", "帝国的", "抗战", "征伐", "铁骑"
        ),
        Genre.LIGHT_NOVEL to listOf(
            "转生", "异世界", "勇者", "魔王", "轻小说", "魔法少女",
            "underworld", "神隐", "学院", "二次元", "乙女",
            "圣女", "青春", "恋爱物语", "物语"
        ),
        Genre.GAME to listOf(
            "网游", "游戏", "电竞", "副本", "开挂", "氪金",
            "全职业", "等级", "boss", "服务器", "刀剑神域", "sao",
            "超神", "登顶", "榜一", "通关", "转生就"
        )
    )

    /** 简介关键词 → 分类。中信号，可叠加。 */
    private val INTRO_RULES: Map<Genre, List<String>> = mapOf(
        Genre.FANTASY to listOf(
            "修炼", "功法", "灵力", "斗气", "武魂", "异界", "神格", "境界",
            "大陆", "先天", "宗门", "化神"
        ),
        Genre.WUXIA to listOf(
            "剑修", "仙门", "丹田", "灵气", "道侣", "仙人", "飞升", "武道",
            "内门", "长老", "秘境", "剑意"
        ),
        Genre.URBAN to listOf(
            "都市", "公司", "集团", "相亲", "恋爱", "暗恋", "初恋", "结婚",
            "分手", "总裁", "学校", "同学"
        ),
        Genre.SCIFI to listOf(
            "飞船", "星系", "人工智能", "基因", "病毒", "辐射", "机器人",
            "舰队", "殖民地", "能源", "实验基地", "未来世界"
        ),
        Genre.MYSTERY to listOf(
            "凶案", "死者", "线索", "警察", "犯罪", "邪教", "诅咒",
            "怨灵", "符咒", "下葬", "棺材", "血迹"
        ),
        Genre.HISTORY to listOf(
            "皇帝", "将军", "朝廷", "宰相", "王朝", "战争", "敌国",
            "边境", "军师", "变法", "起义", "藩王"
        ),
        Genre.LIGHT_NOVEL to listOf(
            "高中生", "社团", "异世界", "召唤", "魔法", "学院",
            "青梅竹马", "傲娇", "女仆", "公主",
            // 日系轻小说的典型叙事词
            "同学", "日常", "青春", "恋爱物语", "同居",
            "弟子", "棋手", "异世界", "转生", "教皇", "圣女"
        ),
        Genre.GAME to listOf(
            "玩家", "副本", "公会", "职业", "技能", "经验值",
            "打怪", "装备", "排名", "竞技场",
            // 真实数据里最高频的几个，缺了它们整片 VRMMO 题材都会被漏
            "虚拟实境", "vr游戏", "vr", "全息游戏", "全息", "死亡游戏",
            "登出", "在线人数", "任务面板", "等级提升", "刷怪",
            "游戏世界", "游戏舞台", "游戏经验"
        )
    )

    /** 属性标签 → 关键词。 */
    private val ATTRIBUTE_RULES: Map<String, List<String>> = mapOf(
        "系统" to listOf("系统", "签到", "面板", "金手指"),
        "穿越" to listOf("穿越", "重生", "回到", "转生"),
        "无敌" to listOf("无敌", "最强", "天下第一", "秒杀"),
        "甜宠" to listOf("甜", "宠爱", "撒糖", "甜宠"),
        "虐心" to listOf("虐", "be", "生死离别", "背叛"),
        "群像" to listOf("群像", "众人", "群雄"),
        "单女主" to listOf("单女主", "只有一个她"),
        "无女主" to listOf("无女主", "不谈恋爱", "专注搞事业"),
        "种田" to listOf("种田", "基建", "发展", "建设"),
        "无限流" to listOf("无限流", "轮回世界", "任务世界"),
        "综漫" to listOf("综漫", "动漫", "同人")
    )

    /**
     * R18 细分词库。
     *
     * 顺序有意义：先匹配到的细分标签会优先进入结果，
     * 所以把更具体的（母子/NTR）放在更泛的（人妻）前面。
     */
    private val ADULT_RULES: Map<AdultTag, List<String>> = mapOf(
        AdultTag.MOTHER_SON to listOf(
            "母子", "妈妈", "母亲", "继母", "后妈", "岳母",
            "母上", "娘亲", "亲妈"
        ),
        AdultTag.NTR to listOf(
            "ntr", "NTR", "绿帽", "出轨", "偷情", "情人", "戴绿",
            "妻子被", "别人的妻子", "出轨"
        ),
        AdultTag.CAMPUS to listOf(
            "校园纯爱", "学生", "老师", "学姐", "学妹", "同桌",
            "班花", "校服", "教室", "宿舍"
        ),
        AdultTag.YURI to listOf("百合", "les", "女同", "姐妹恋", "gl"),
        AdultTag.BDSM to listOf(
            "调教", "触手", "捆绑", "sm", "SM", "奴", "支配",
            "催眠", "驯服", "项圈"
        ),
        AdultTag.MATURE_WOMAN to listOf(
            "人妻", "熟女", "少妇", "寡妇", "师娘", "老板娘",
            "御姐", "姐姐"
        )
    )

    /** 书源 kind 里的站内标签 → 一级分类。 */
    private fun genreOfToken(t: String): Genre? {
        val s = t.lowercase()
        return when {
            s.contains("玄幻") || s.contains("奇幻") || s.contains("异世") ||
                s.contains("东方玄幻") || s.contains("西方奇幻") -> Genre.FANTASY
            s.contains("仙侠") || s.contains("武侠") || s.contains("修真") ||
                s.contains("古典仙侠") -> Genre.WUXIA
            s.contains("都市") || s.contains("言情") || s.contains("现代") ||
                s.contains("青春") || s.contains("婚恋") -> Genre.URBAN
            s.contains("科幻") || s.contains("末世") || s.contains("星际") ||
                s.contains("未来") -> Genre.SCIFI
            s.contains("悬疑") || s.contains("灵异") || s.contains("恐怖") ||
                s.contains("推理") -> Genre.MYSTERY
            s.contains("历史") || s.contains("军事") || s.contains("战争") ->
                Genre.HISTORY
            s.contains("轻小说") || s.contains("二次元") || s.contains("日系") ->
                Genre.LIGHT_NOVEL
            s.contains("游戏") || s.contains("竞技") || s.contains("网游") -> Genre.GAME
            s.contains("成人") || s.contains("r18") || s.contains("情色") -> Genre.ADULT
            else -> null
        }
    }
}
