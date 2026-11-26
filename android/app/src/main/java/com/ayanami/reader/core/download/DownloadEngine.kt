package com.ayanami.reader.core.download

import com.ayanami.reader.core.model.Book
import com.ayanami.reader.core.model.BookChapter
import com.ayanami.reader.core.model.BookSource
import com.ayanami.reader.core.pipeline.SourceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 章节离线缓存引擎。
 *
 * ### 为什么不能简单地 `for (chapter in toc) fetch(chapter)`
 *
 * 三个现实问题：
 *
 * 1. **按域名限流**：一个源的几百章全在同一域名下，无节制并发会被判成
 *    CC 攻击（轻则 403，重则 IP 进黑名单）。这里按 host 做闸门，默认 2 并发。
 * 2. **断点续传**：用户可能下到第 200 章退出。已缓存的章节必须跳过，
 *    不能重下 —— 否则每次「继续下载」都从头来。
 * 3. **失败重试**：单章失败不该让整个任务挂掉。失败章节收集起来，
 *    最后重试一轮（很多失败是瞬时网络抖动）。
 *
 * ### 存储布局
 *
 * ```
 * cache/
 *   <bookKey>/          bookKey = sha1(bookUrl) 前 16 位
 *     meta.json         {name, author, origin, toc: [...]}
 *     0001.txt
 *     0002.txt
 * ```
 *
 * 用 `sha1` 而不是书名做目录名：书名可能含 `/`、`:`、`?` 等非法字符，
 * 也可能重名。sha1 保证唯一且文件系统安全。
 */
class DownloadEngine(
    private val scope: CoroutineScope,
    private val cacheRoot: File
) {

    /**
     * 下载任务状态。
     *
     * ⚠️ `completed` / `state` / `failedCount` 必须是 **Compose 可观测**的类型。
     *
     * 之前用 `AtomicInteger` + `@Volatile`：数据是对的，但 Compose 看不到变化 ——
     * UI 只在第一次重组时读到 "4/50"，之后永远停在 4，看起来像下载卡死。
     * （实测就是这个现象：后台在正常跑，界面一动不动。）
     */
    class Task(
        val bookKey: String,
        val bookName: String,
        val total: Int
    ) {
        /** 已完成章数。Compose state，改它会触发重组。 */
        internal val completedState = androidx.compose.runtime.mutableStateOf(0)
        val completed: Int get() = completedState.value

        /** 失败章数。 */
        internal val failedCountState = androidx.compose.runtime.mutableStateOf(0)
        val failedCount: Int get() = failedCountState.value

        /** 失败的章节下标（后台用，UI 不看）。 */
        internal val failed: MutableList<Int> =
            java.util.Collections.synchronizedList(ArrayList())

        internal val stateState = androidx.compose.runtime.mutableStateOf(State.IDLE)
        var state: State
            get() = stateState.value
            internal set(v) { stateState.value = v }

        @Volatile var error: String? = null
        @Volatile var job: Job? = null

        enum class State { IDLE, RUNNING, PAUSED, DONE, FAILED }

        val progress: Float
            get() = if (total <= 0) 0f else completed.toFloat() / total

        internal fun bumpCompleted() {
            completedState.value += 1
            // failedCount 也要跟着更新，否则重试成功后 UI 还显示旧失败数
            failedCountState.value = failed.size
        }

        internal fun syncFailed() {
            failedCountState.value = failed.size
        }
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    /** 按域名的并发闸门。 */
    private val hostGates = ConcurrentHashMap<String, Semaphore>()

    private fun gateFor(url: String): Semaphore {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return hostGates.getOrPut(host) { Semaphore(hostConcurrency) }
    }

    /** 每个域名的最大并发。太高会被站方判成攻击。 */
    @Volatile
    var hostConcurrency: Int = 2

    /** 单章失败重试次数。 */
    @Volatile
    var retryPerChapter: Int = 2

    // ---------------- 缓存读写 ----------------

    fun bookKey(bookUrl: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(bookUrl.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun bookDir(bookUrl: String): File = File(cacheRoot, bookKey(bookUrl))

    private fun chapterFile(bookUrl: String, index: Int): File =
        File(bookDir(bookUrl), "%04d.txt".format(index))

    /** 这一章是否已缓存。 */
    fun isCached(bookUrl: String, index: Int): Boolean {
        val f = chapterFile(bookUrl, index)
        return f.exists() && f.length() > 0
    }

    /** 读已缓存的正文。 */
    fun readCached(bookUrl: String, index: Int): String? {
        val f = chapterFile(bookUrl, index)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun writeCached(bookUrl: String, index: Int, title: String, text: String) {
        val dir = bookDir(bookUrl)
        if (!dir.exists()) dir.mkdirs()
        val f = chapterFile(bookUrl, index)
        // 第一行存标题，正文从第二行开始 —— 离线阅读时不用再查 meta
        f.writeText("$title\n$text")
    }

    /** 已缓存的章节数。 */
    fun cachedCount(bookUrl: String, total: Int): Int =
        (0 until total).count { isCached(bookUrl, it) }

    /** 清掉一本书的缓存。 */
    fun clearBook(bookUrl: String): Boolean {
        val dir = bookDir(bookUrl)
        return dir.exists() && dir.deleteRecursively()
    }

    /** 缓存占用的总字节数。 */
    fun totalCacheBytes(): Long {
        if (!cacheRoot.exists()) return 0
        return cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---------------- 下载 ----------------

    fun taskOf(bookUrl: String): Task? = tasks[bookKey(bookUrl)]

    /**
     * 开始下载（或从断点继续）。
     *
     * @param ranges 要下的章节下标集合。空表示全部
     * @param maxCount 最多下多少章（「缓存后续 N 章」用）
     */
    fun start(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        ranges: Collection<Int>? = null,
        maxCount: Int? = null,
        onProgress: (Task) -> Unit = {}
    ): Task {
        val key = bookKey(book.bookUrl)
        val existing = tasks[key]
        if (existing?.state == Task.State.RUNNING) return existing

        // 计算目标章节：跳过已缓存的，再受 maxCount 限制
        var targets = (ranges ?: toc.indices.toList())
            .filter { it in toc.indices }
            .filterNot { isCached(book.bookUrl, it) }
            .sorted()
        if (maxCount != null) targets = targets.take(maxCount)

        val task = Task(
            bookKey = key,
            bookName = book.name,
            total = targets.size
        )
        task.state = Task.State.RUNNING
        tasks[key] = task

        if (targets.isEmpty()) {
            task.state = Task.State.DONE
            onProgress(task)
            return task
        }

        task.job = scope.launch {
            try {
                // 第一轮：正常下载
                for (idx in targets) {
                    if (!isActive) break
                    val ok = downloadOne(source, book, toc[idx])
                    if (ok) task.bumpCompleted() else { task.failed.add(idx); task.syncFailed() }
                    onProgress(task)
                }

                // 第二轮：重试失败章节。瞬时抖动很常见，重试一轮能救回大半
                var round = 0
                while (task.failed.isNotEmpty() && round < retryPerChapter && isActive) {
                    round++
                    val retry = task.failed.toList()
                    task.failed.clear()
                    for (idx in retry) {
                        if (!isActive) break
                        val ok = downloadOne(source, book, toc[idx])
                        if (ok) task.bumpCompleted() else { task.failed.add(idx); task.syncFailed() }
                        onProgress(task)
                    }
                }

                task.state = when {
                    !isActive -> Task.State.PAUSED
                    task.failed.isEmpty() -> Task.State.DONE
                    else -> Task.State.FAILED
                }
                task.syncFailed()
                if (task.failed.isNotEmpty()) {
                    task.error = "${task.failed.size} 章下载失败"
                }
            } catch (e: Exception) {
                task.state = Task.State.FAILED
                task.error = e.message ?: e.javaClass.simpleName
            } finally {
                onProgress(task)
            }
        }
        return task
    }

    /** 暂停。已下载的保留（下次 start 会跳过）。 */
    fun pause(bookUrl: String) {
        tasks[bookKey(bookUrl)]?.job?.cancel()
        tasks[bookKey(bookUrl)]?.state = Task.State.PAUSED
    }

    private suspend fun downloadOne(
        source: BookSource,
        book: Book,
        chapter: BookChapter
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            gateFor(chapter.url).withPermit {
                val session = SourceSession(source, polite = true)
                val text = session.content(chapter, maxPages = MAX_CONTENT_PAGES)
                if (text == null || text.text.isBlank()) return@withPermit false
                writeCached(book.bookUrl, chapter.index, chapter.title, text.text)
                true
            }
        }.getOrDefault(false)
    }

    // ---------------- 导出 ----------------

    /**
     * 导出整本 TXT。
     *
     * 只导出**已缓存**的章节 —— 没下的章节没法凭空生成。
     * 章节之间用空行分隔，标题前后各留一行。
     */
    fun exportTxt(book: Book, toc: List<BookChapter>, target: File): Result<Int> = runCatching {
        var count = 0
        val sb = StringBuilder()
        sb.append(book.name).append("\n")
        if (book.author.isNotBlank()) sb.append("作者：").append(book.author).append("\n")
        book.intro?.takeIf { it.isNotBlank() }?.let {
            sb.append("\n").append(it).append("\n")
        }
        sb.append("\n").append("=".repeat(30)).append("\n\n")

        for (chapter in toc) {
            val raw = readCached(book.bookUrl, chapter.index) ?: continue
            // 缓存文件第一行是标题
            val body = raw.substringAfter('\n', "")
            sb.append(chapter.title).append("\n\n")
            sb.append(body.trim()).append("\n\n\n")
            count++
        }

        if (count == 0) throw IllegalStateException("没有已缓存的章节，先下载再导出")
        target.parentFile?.mkdirs()
        target.writeText(sb.toString())
        count
    }

    private companion object {
        /** 下载时的正文分页上限（离线要拿完整章）。 */
        const val MAX_CONTENT_PAGES = 20
    }
}
