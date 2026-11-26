package com.ayanami.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ayanami.reader.core.bookstore.BookstoreEngine
import com.ayanami.reader.core.classify.BookClassifier

/**
 * 书城页的 UI 状态。
 *
 * 独立于 [ReaderUiState] —— 书城的筛选条件（3 排胶囊 + 排序）维度多，
 * 全塞进主 state 会让那个类变得难读，且书城是唯一会用到它们的地方。
 *
 * 用 Compose 的 `mutableStateOf` 而不是 `StateFlow`：书城的筛选是
 * **纯 UI 派生**（过滤 + 排序都是内存操作，没有 IO），
 * 走 Compose 快照系统能让筛选立刻生效，不需要额外一次 collect。
 */
class BookstoreState {

    /** 全部已加载的书（跨分类混排）。 */
    var allBooks by mutableStateOf<List<BookstoreEngine.StoreBook>>(emptyList())

    /** 可逛的源。 */
    var sources by mutableStateOf<List<BookstoreEngine.StoreSource>>(emptyList())
        private set

    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
        private set

    var errors by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** 第 1 排：主分类。`ALL` 表示全部。 */
    var genre by mutableStateOf("ALL")

    /** 第 2 排：内容分级。`ALL` / `GENERAL` / `ADULT`。 */
    var rating by mutableStateOf("ALL")

    /** 第 3 排：书源筛选。`ALL` 或具体 bookSourceUrl。 */
    var sourceFilter by mutableStateOf("ALL")

    /** R18 细分分类（`null` = 不限）。仅在 R18 开关打开时可选。 */
    var adultSubGenre by mutableStateOf<String?>(null)

    /** 排序。 */
    var sort by mutableStateOf("DEFAULT")

    /** 是否已做过首屏加载。 */
    var initialized by mutableStateOf(false)

    fun setBooks(books: List<BookstoreEngine.StoreBook>, more: Boolean) {
        allBooks = books
        hasMore = more
    }

    fun appendBooks(books: List<BookstoreEngine.StoreBook>, more: Boolean) {
        // 按 id 去重后追加
        val existing = allBooks.mapTo(HashSet()) { it.id }
        allBooks = allBooks + books.filterNot { it.id in existing }
        hasMore = more
    }

    fun updateSources(list: List<BookstoreEngine.StoreSource>) {
        sources = list
    }

    fun reset() {
        allBooks = emptyList()
        hasMore = true
        initialized = false
        genre = "ALL"
        rating = "ALL"
        adultSubGenre = null
        sourceFilter = "ALL"
        sort = "DEFAULT"
    }

    /** 当前生效的筛选结果（每次重组现算，书量 ≤ 数百本，够快）。 */
    fun visible(engine: BookstoreEngine, adultOn: Boolean): List<BookstoreEngine.StoreBook> {
        var list = allBooks
        // R18 兜底：即使书城层已经过滤过，这里再挡一次 ——
        // 设置可能在加载后才被关掉
        if (!adultOn) list = list.filterNot { it.classification.isAdult }
        list = engine.filterByGenre(list, genre)
        list = engine.filterByRating(list, rating)
        // 成人细分（母子/NTR/百合…）是二级过滤，叠在分级之上
        adultSubGenre?.let { sub ->
            val tag = runCatching {
                BookClassifier.AdultTag.valueOf(sub)
            }.getOrNull()
            if (tag != null) list = list.filter { tag in it.classification.adultTags }
        }
        list = engine.filterBySource(list, sourceFilter)
        return engine.sort(list, sort)
    }

    /** 当前可用的主分类（R18 关闭时不含成人向）。 */
    fun genres(adultOn: Boolean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("ALL" to "全部")
        for (g in BookCatalog.GENRES) {
            if (!adultOn && g == BookClassifier.Genre.ADULT) continue
            out.add(g.name to g.label)
        }
        return out
    }

    /** R18 细分分类（仅在开关打开时出现）。 */
    fun adultGenres(adultOn: Boolean): List<Pair<String, String>> {
        if (!adultOn) return emptyList()
        return BookCatalog.ADULT_TAGS.map { it.name to it.label }
    }
}

/** 分类目录常量。 */
object BookCatalog {
    val GENRES = listOf(
        BookClassifier.Genre.FANTASY,
        BookClassifier.Genre.WUXIA,
        BookClassifier.Genre.URBAN,
        BookClassifier.Genre.SCIFI,
        BookClassifier.Genre.MYSTERY,
        BookClassifier.Genre.HISTORY,
        BookClassifier.Genre.LIGHT_NOVEL,
        BookClassifier.Genre.GAME,
        BookClassifier.Genre.ADULT,
        BookClassifier.Genre.OTHER
    )

    val ADULT_TAGS = listOf(
        BookClassifier.AdultTag.MOTHER_SON,
        BookClassifier.AdultTag.CAMPUS,
        BookClassifier.AdultTag.NTR,
        BookClassifier.AdultTag.YURI,
        BookClassifier.AdultTag.BDSM,
        BookClassifier.AdultTag.MATURE_WOMAN
    )
}
