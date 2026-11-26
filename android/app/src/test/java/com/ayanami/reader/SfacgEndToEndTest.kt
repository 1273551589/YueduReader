package com.ayanami.reader

import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceChecker
import com.ayanami.reader.core.pipeline.SourceImporter
import com.ayanami.reader.core.pipeline.SourceSession
import com.ayanami.reader.core.rule.LenientJson
import com.ayanami.reader.mod.ModLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 端到端：导入 → 体检 → 搜索 → 详情 → 目录 → 正文。
 *
 * 会真的联网（真实书源），所以属于集成测试而非纯单元测试。
 * 用 `-DskipOnline` 跳过。
 */
class SfacgEndToEndTest {

    private fun readSourceFileTheHardWay(): String {
        // 测试的工作目录是 app/，书源在仓库根的 sources/
        val candidates = listOf(
            File("../sources/sfacg-lightnovel.json"),
            File("../../sources/sfacg-lightnovel.json"),
            File("sources/sfacg-lightnovel.json")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到书源文件，cwd=${File(".").absolutePath}")
        return f.readText()
    }

    private fun source(): BookSource {
        val json = readSourceFileTheHardWay()
        val r = SourceImporter.parse(json)
        assertEquals("导入应成功", 1, r.sources.size)
        assertTrue("不应有无效条目：${r.invalid}", r.invalid.isEmpty())
        return r.sources.first()
    }

    @Test
    fun `import parses the real book source`() {
        val src = source()
        assertEquals("SF轻小说", src.bookSourceName)
        assertEquals("https://book.sfacg.com", src.bookSourceUrl)
        assertTrue("搜索 URL 应含 {{key}}", src.searchUrl!!.contains("{{key}}"))
        assertEquals("ul[style] > li:not(.Conjunction)", src.ruleSearch.bookList)
        assertTrue("正文规则应有内容", src.ruleContent.content!!.isNotBlank())
    }

    @Test
    fun `import dedupes by url`() {
        val json = readSourceFileTheHardWay()
        val r = SourceImporter.parse(json)
        val again = SourceImporter.parse(json, r.sources.map { it.bookSourceUrl }.toSet())
        assertEquals(0, again.sources.size)
        assertEquals(1, again.duplicates.size)
    }

    @Test
    fun `group object form is accepted`() {
        val grouped = """{"轻小说":[{"bookSourceUrl":"https://x.com","bookSourceName":"X"}]}"""
        val r = SourceImporter.parse(grouped)
        assertEquals(1, r.sources.size)
        assertEquals(listOf("轻小说"), r.groupNames)
        assertEquals("轻小说", r.sources.first().bookSourceGroup)
    }

    @Test
    fun `full pipeline reaches real content`() = runBlocking {
        if (System.getProperty("skipOnline") != null) return@runBlocking
        val src = source()
        val session = SourceSession(src, polite = true)

        val hits = session.search("刀剑")
        assertTrue("搜索应有结果，实际 error=${session.lastError?.humanMessage}", hits.isNotEmpty())
        val hit = hits.first()
        assertTrue("书名不该为空", hit.name.isNotBlank())
        assertTrue("bookUrl 应是绝对地址：${hit.bookUrl}",
            hit.bookUrl.startsWith("http"))
        println("搜索命中 ${hits.size} 条，第一条=${hit.name} / ${hit.author} / ${hit.bookUrl}")
        println("简介=" + (hit.intro ?: ""))
        println("更新时间=" + (hit.updateTime ?: ""))

        val info = session.bookInfo(hit.bookUrl)
        assertTrue("详情应有书名", info != null && info.name.isNotBlank())
        println("详情：${info!!.name} / ${info.author} / 分类=${info.kind} / 字数=${info.wordCount}")

        val toc = session.toc(info)
        assertTrue("目录应有章节，实际 error=${session.lastError?.humanMessage}", toc.size > 3)
        println("目录 ${toc.size} 章，第一章=${toc.first().title}")

        // 找一个有真实正文的章节（跳过封面/彩插）
        var ok = false
        for (ch in toc.take(8)) {
            if (ch.title.contains("封面") || ch.title.contains("彩插") || ch.title.contains("版权")) continue
            val text = session.content(ch)
            if (text != null && text.text.length > 200) {
                println("正文抽样：${ch.title} 共 ${text.text.length} 字")
                println("开头：${text.text.take(120)}")
                assertTrue("中文占比应够：判据=${SourceChecker.isReadable(text.text)}",
                    SourceChecker.isReadable(text.text))
                ok = true
                break
            }
        }
        assertTrue("应至少抽到一章可读正文", ok)
    }

    @Test
    fun `checker reports readable`() = runBlocking {
        if (System.getProperty("skipOnline") != null) return@runBlocking
        val src = source()
        val r = SourceChecker.check(src, testBook = "刀剑", polite = true)
        println("体检：${r.status.label} / ${r.elapsedMs}ms / 卡在=${r.stuckAt} / ${r.detail}")
        assertEquals(SourceChecker.UiStatus.READABLE, r.status)
        assertEquals("done", r.stuckAt)
    }

    @Test
    fun `mod theme is parsed`() {
        val themeJson = """{"primary":"#3B6FE0","background":"#FFFFFF","cornerRadius":12}"""
        val manifest = """
            {"id":"my-theme","name":"暗色主题","version":"1.2.0","type":"theme",
             "entry":"theme.json","priority":10}
        """.trimIndent()

        val files = mapOf(
            "/mods/my-theme/mod.json" to manifest,
            "/mods/my-theme/theme.json" to themeJson
        )
        val r = ModLoader.load(listOf("/mods/my-theme")) { files[it] }
        assertTrue("不该有错误：${r.errors}", r.errors.isEmpty())
        assertEquals(1, r.mods.size)
        val mod = r.mods.first()
        assertEquals("my-theme", mod.manifest.id)
        assertEquals(ModLoader.ModType.theme, mod.manifest.type)
        // 先断言非空再访问 —— 这样既没有「unnecessary !!」警告，
        // 又不会因为编译器认为它可空而报错
        val theme = requireNotNull(mod.theme) { "主题 MOD 应解析出 theme" }
        assertEquals(12f, theme.cornerRadius)
        assertTrue(theme.primary != null)
    }

    @Test
    fun `bad mod is skipped not crashed`() {
        val files = mapOf(
            "/m1/mod.json" to "{not json",
            "/m2/mod.json" to """{"id":"x","type":"nonsense"}""",
            "/m3/mod.json" to """{"name":"no id","type":"theme"}"""
        )
        val r = ModLoader.load(listOf("/m1", "/m2", "/m3")) { files[it] }
        assertEquals(0, r.mods.size)
        assertEquals(2, r.errors.size)   // m1 坏 JSON、m3 缺 id；m2 未知 type 静默忽略
    }

    @Test
    fun `mod sources bundle is importable`() {
        val srcJson = """[{"bookSourceUrl":"https://b.com","bookSourceName":"B2"}]"""
        val files = mapOf(
            "/ms/mod.json" to """{"id":"my-sources","type":"sources","entry":"sources/s.json"}""",
            "/ms/sources/s.json" to srcJson
        )
        val r = ModLoader.load(listOf("/ms")) { files[it] }
        assertEquals(1, r.mods.size)
        val imported = SourceImporter.parse(r.mods.first().sourceJson!!)
        assertEquals(1, imported.sources.size)
    }
}
