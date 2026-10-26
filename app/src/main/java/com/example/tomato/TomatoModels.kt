package com.example.tomato

import org.json.JSONArray
import org.json.JSONObject

/** A Fanqie (番茄小说) book as the engine describes it. Text fields are the original Chinese. */
data class TomatoBook(
    val bookId: String,
    val title: String,
    val author: String,
    val abstractText: String,
    val coverUrl: String,
    val category: String,
    /** "已完结" / "连载中" as given by Fanqie. */
    val serialStatus: String,
    val wordCount: Long,
    val chapterCount: Int,
    val readCount: Long,
    /** Seconds since epoch of the newest chapter, or 0. */
    val lastChapterTime: Long
) {
    val isCompleted: Boolean get() = serialStatus.contains("完结") || serialStatus.contains("完本")
}

/** A download job in the engine (from `list_jobs`). */
data class TomatoJob(
    val id: String,
    val bookId: String,
    val status: String,
    val percent: Int,
    val done: Int,
    val total: Int,
    val message: String,
    val outputPath: String?,
    val missingChapters: Int
) {
    val isFinishedOk: Boolean get() = status.lowercase() in setOf("done", "success", "completed")
    val isPartial: Boolean get() = status.equals("partial", ignoreCase = true)
    val isFailed: Boolean get() = status.lowercase() in setOf("failed", "error")
    val isCanceled: Boolean get() = status.lowercase() in setOf("canceled", "cancelled")
    val isPaused: Boolean get() = status.equals("paused", ignoreCase = true)
    val isTerminal: Boolean get() = isFinishedOk || isPartial || isFailed || isCanceled
}

object TomatoJson {
    fun parseBook(json: JSONObject): TomatoBook? {
        val source = json.optJSONObject("book") ?: json
        val id = source.opt("book_id")?.toString()?.trim().orEmpty()
        if (!TomatoIds.isBookId(id)) return null
        val title = source.optString("book_name").ifBlank { source.optString("title") }.trim()
        return TomatoBook(
            bookId = id,
            title = title,
            author = source.optString("author").trim(),
            abstractText = source.optString("abstract").ifBlank { source.optString("description") }.trim(),
            coverUrl = source.optString("cover_url").ifBlank { source.optString("thumb_url") }.trim(),
            category = source.optString("category").trim(),
            serialStatus = source.optString("status").trim(),
            wordCount = source.opt("word_count").asLong(),
            chapterCount = source.opt("chapter_count").asLong().toInt(),
            readCount = source.opt("read_count").asLong(),
            lastChapterTime = source.opt("last_chapter_first_pass_time").asLong()
        )
    }

    fun parseSearch(json: JSONObject): List<TomatoBook> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::parseBook) }
            .distinctBy { it.bookId }
    }

    fun parseJobs(json: JSONObject): List<TomatoJob> {
        val items = json.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            parseJob(item)
        }
    }

    fun parseJob(item: JSONObject): TomatoJob? {
        val id = item.optString("id")
        if (id.isBlank()) return null
        return TomatoJob(
            id = id,
            bookId = item.opt("book_id")?.toString().orEmpty(),
            status = item.optString("status"),
            percent = item.opt("progress").asLong().toInt().coerceIn(0, 100),
            done = item.opt("progress_done").asLong().toInt(),
            total = item.opt("progress_total").asLong().toInt(),
            message = item.optString("message"),
            outputPath = item.optString("output_path").takeIf { it.isNotBlank() },
            missingChapters = when (val missing = item.opt("missing_chapters")) {
                is JSONArray -> missing.length()
                is Number -> missing.toInt()
                else -> 0
            }
        )
    }

    /** Numbers arrive as numbers or strings ("3201288"). */
    private fun Any?.asLong(): Long = when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull() ?: 0L
        else -> 0L
    }
}

object TomatoIds {
    private val BOOK_ID = Regex("""^\d{15,22}$""")
    private val PAGE_URL = Regex("""fanqienovel\.com/page/(\d{15,22})""")
    private val QUERY_PARAM = Regex("""[?&](?:book_id|bookId|bookid)=(\d{15,22})""")
    private val ANY_ID = Regex("""(?<!\d)(\d{15,22})(?!\d)""")

    fun isBookId(text: String): Boolean = BOOK_ID.matches(text)

    /**
     * The book ID in what the user typed: a bare ID, a book page link, or a share text containing one.
     * Reader links (fanqienovel.com/reader/...) carry a chapter ID, not a book ID, so they are rejected.
     */
    fun extract(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (isBookId(text)) return text
        PAGE_URL.find(text)?.let { return it.groupValues[1] }
        QUERY_PARAM.find(text)?.let { return it.groupValues[1] }
        if (text.contains("/reader/")) return null
        // Share texts like "《书名》… https://… 7143038691944959011"
        val ids = ANY_ID.findAll(text).map { it.groupValues[1] }.distinct().toList()
        return ids.singleOrNull()
    }

    fun looksLikeLink(input: String): Boolean = input.contains("://") || input.contains("fanqienovel")
}

/** Readable English for Fanqie's serial status. */
fun TomatoBook.statusLabel(): String = when {
    isCompleted -> "Completed"
    serialStatus.contains("连载") -> "Ongoing"
    serialStatus.isBlank() -> ""
    else -> serialStatus
}

/** "3.2M words" style count (Chinese characters are counted as words by Fanqie). */
fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(java.util.Locale.US, "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(java.util.Locale.US, "%.0fK", value / 1_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
