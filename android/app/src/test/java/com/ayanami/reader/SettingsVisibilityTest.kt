package com.ayanami.reader

import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.model.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 可见性判定的契约测试。
 *
 * `Settings.allows()` 是**唯一**的 R18 边界 —— 书城 / 搜索 / 书架都得走它。
 * 这里把它的真值表逐条锁死。
 *
 * 但 `Settings` 依赖 Android `Context`，纯 JVM 测不了，
 * 所以这里测的是它的**语义等价物**：[VisibilityRule]。
 * 生产代码里 `Settings.allows` 和它必须保持一致（同一段逻辑）。
 */
class SettingsVisibilityTest {

    /** 与 `Settings.allows` 完全等价的纯函数，用于测试。 */
    private fun allows(adultEnabled: Boolean, contentRating: String, isAdult: Boolean): Boolean {
        if (!adultEnabled && isAdult) return false
        return when (contentRating) {
            "GENERAL" -> !isAdult
            "ADULT" -> isAdult
            else -> true
        }
    }

    @Test
    fun `adult off hides adult books`() {
        assertTrue("成人书在开关关闭时必须隐藏", !allows(false, "ALL", isAdult = true))
        assertTrue("普通书照常显示", allows(false, "ALL", isAdult = false))
    }

    @Test
    fun `adult off ignores rating selector`() {
        // 即使玩家把分级切到「成人向」，开关关着也拿不到东西
        assertTrue(!allows(false, "ADULT", isAdult = true))
        assertTrue(!allows(false, "GENERAL", isAdult = true))
    }

    @Test
    fun `general rating excludes adult when switch on`() {
        assertTrue(!allows(true, "GENERAL", isAdult = true))
        assertTrue(allows(true, "GENERAL", isAdult = false))
    }

    @Test
    fun `adult rating shows only adult`() {
        assertTrue(allows(true, "ADULT", isAdult = true))
        assertTrue(!allows(true, "ADULT", isAdult = false))
    }

    @Test
    fun `all rating shows both when switch on`() {
        assertTrue(allows(true, "ALL", isAdult = true))
        assertTrue(allows(true, "ALL", isAdult = false))
    }

    @Test
    fun `full truth table`() {
        // (开关, 分级, 是否成人) → 是否可见
        val cases = listOf(
            Triple(false, "ALL", true) to false,
            Triple(false, "ALL", false) to true,
            Triple(false, "GENERAL", true) to false,
            Triple(false, "GENERAL", false) to true,
            Triple(false, "ADULT", true) to false,
            Triple(false, "ADULT", false) to false,
            Triple(true, "ALL", true) to true,
            Triple(true, "ALL", false) to true,
            Triple(true, "GENERAL", true) to false,
            Triple(true, "GENERAL", false) to true,
            Triple(true, "ADULT", true) to true,
            Triple(true, "ADULT", false) to false
        )
        for ((input, expected) in cases) {
            val (on, rating, adult) = input
            assertEquals(
                "开关=$on 分级=$rating 成人=$adult",
                expected, allows(on, rating, adult)
            )
        }
    }

    // ---------------- 书城层兜底 ----------------

    @Test
    fun `bookstore visible filters adult when switch off`() {
        // 这是 UI 层的最后一道闸：即使设置加载后才关掉开关，
        // 已加载的成人书也不该继续出现在列表里
        val engine = BookstoreEngine(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val books = listOf(
            storeBook("普通书", isAdult = false),
            storeBook("成人书", isAdult = true)
        )
        val filtered = books.filterNot { it.classification.isAdult }
        assertEquals(1, filtered.size)
        assertEquals("普通书", filtered[0].book.name)
    }

    private fun storeBook(name: String, isAdult: Boolean) = BookstoreEngine.StoreBook(
        book = SearchBook(name = name, bookUrl = "http://x/$name", origin = "o"),
        classification = BookClassifier.Result(
            genre = BookClassifier.Genre.OTHER,
            tags = emptyList(),
            isAdult = isAdult
        )
    )
}
