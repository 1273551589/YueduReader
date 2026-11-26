package com.ayanami.reader.core.bookstore

import com.ayanami.reader.core.classify.BookClassifier
import com.ayanami.reader.core.model.SearchBook
import com.ayanami.reader.core.rule.LenientJson
import java.io.File

/**
 * 书城首屏快照。
 *
 * ### 为什么需要它
 *
 * 书城首屏要并发拉 N 个源的发现页，实测冷启动到有内容要 4–12 秒
 * （取决于源的数量和网速）。这段时间用户面对的是空列表 + 转圈 ——
 * 而有内容之后，它们其实**和上次几乎一样**。
 *
 * 所以：把上一次聚合结果落盘，冷启动先秒出快照，同时后台静默刷新。
 * 用户看到的是「立刻有内容，过一会儿默默换上更新的」。
 *
 * ### 有效期 30 分钟
 *
 * 太短没意义（书城更新频率本来就不高），太长会显示已经下架的书。
 * 过期**不删除**，只是不再用它垫首屏 —— 见 [load] 的 `allowStale`。
 *
 * ### 为什么不存 classification
 *
 * `BookClassifier.Result` 里有 enum 和列表，反序列化容易随版本漂移
 * （加一个 enum 值，旧快照就读不出来了）。分类是**纯本地计算**、
 * 纳秒级，重新算一遍比反序列化更稳。所以快照只存原始 [SearchBook]。
 */
object BookstoreSnapshot {

    /** 快照有效期。 */
    const val TTL_MS = 30 * 60 * 1000L

    /** 落盘形态。字段命名保持精简，减小文件体积。 */
    internal data class Payload(
        val savedAt: Long = 0,
        val books: List<SearchBook> = emptyList()
    )

    /** 一份可用的快照。 */
    data class Snapshot(
        val books: List<BookstoreEngine.StoreBook>,
        val savedAt: Long
    ) {
        val ageMs: Long get() = System.currentTimeMillis() - savedAt
        val isFresh: Boolean get() = ageMs in 0..TTL_MS
    }

    fun file(dir: File): File = File(dir, "bookstoreSnapshot.json")

    /** 保存。失败静默 —— 缓存坏了不该影响主流程。 */
    fun save(dir: File, books: List<BookstoreEngine.StoreBook>) {
        runCatching {
            val payload = Payload(
                savedAt = System.currentTimeMillis(),
                books = books.map { it.book }
            )
            file(dir).writeText(LenientJson.lenient.toJson(payload))
        }
    }

    /**
     * 读取。
     *
     * @param allowStale 是否接受过期快照。冷启动垫首屏用 true（有总比没有好），
     *   判断「要不要刷新」用 [Snapshot.isFresh]。
     */
    fun load(dir: File, allowStale: Boolean = true): Snapshot? {
        val f = file(dir)
        if (!f.exists()) return null
        val payload = runCatching {
            LenientJson.lenient.fromJson(f.readText(), Payload::class.java)
        }.getOrNull() ?: return null
        if (payload.books.isEmpty()) return null

        val snap = Snapshot(payload.books.map { it.toStoreBook() }, payload.savedAt)
        if (!allowStale && !snap.isFresh) return null
        return snap
    }

    fun clear(dir: File) {
        runCatching { file(dir).delete() }
    }

    /** 快照里的书是裸 [SearchBook]，分类现算。 */
    private fun SearchBook.toStoreBook(): BookstoreEngine.StoreBook =
        BookstoreEngine.StoreBook(
            book = this,
            classification = BookClassifier.classify(
                name = name,
                author = author,
                intro = intro,
                kindRaw = kind
            ),
            categoryHint = ""
        )
}
