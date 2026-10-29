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
    // Java's \d is ASCII-only; input is normalized to ASCII digits first.
    private val BOOK_ID = Regex("""^\d{15,22}$""")
    private val PAGE_URL = Regex("""fanqienovel\.com/page/(\d{15,22})""")
    private val QUERY_PARAM = Regex("""[?&](?:book_id|bookId|bookid)=(\d{15,22})""")
    /**
     * An ID inside other text. Letters may come before it ("ID7143…", "%2F7143…" in encoded links),
     * but a number running into letters ("…590O1") is a mistyped ID, not one.
     */
    private val ANY_ID = Regex("""(?<!\d)(\d{15,22})(?![\dA-Za-z])""")
    private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")
    /** Digits typed in groups: "7668 2387 9662 5808 408", "7668-2387-…". */
    private val GROUPED_NUMBER = Regex("""^\d+(?:[ \-.]+\d+)*$""")

    /** Fanqie's book IDs are this long (older books too); shown in messages. */
    const val USUAL_LENGTH = 19

    fun isBookId(text: String): Boolean = BOOK_ID.matches(text)

    /**
     * Cleans typed or pasted text: digits from any script (full-width ０-９, Bengali ০-৯, Arabic-Indic,
     * Devanagari…) become ASCII 0-9, invisible characters (zero-width spaces, direction marks, BOM, soft
     * hyphen) are removed, and other kinds of spaces become plain spaces. Copying an ID from a web page
     * or typing it on a non-English keyboard used to send it to the title search, which returned
     * unrelated novels.
     */
    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in '0'..'9' -> out.append(ch)
                isInvisible(ch) -> Unit
                Character.isDigit(ch) -> {
                    val value = Character.getNumericValue(ch)
                    if (value in 0..9) out.append('0' + value) else out.append(ch)
                }
                ch == '\u00A0' || ch == '\u3000' || Character.isSpaceChar(ch) -> out.append(' ')
                ch == '\uFF0D' || ch == '\u2010' || ch == '\u2011' || ch == '\u2012' || ch == '\u2013' || ch == '\u2014' -> out.append('-')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isInvisible(ch: Char): Boolean =
        ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2060'..'\u2064' ||
            ch in '\u2066'..'\u2069' || ch == '\uFEFF' || ch == '\u00AD' || ch == '\u180E'

    /** [input] without invisible characters (zero-width spaces, direction marks…); nothing else changes. */
    fun stripInvisible(input: String): String = input.filterNot(::isInvisible)

    /**
     * Decodes %XX escapes (twice at most, for links encoded inside other links, such as Google's
     * redirect links). Invalid escapes and '+' are left as they are.
     */
    internal fun percentDecode(input: String): String {
        var text = input
        repeat(2) {
            if (!PERCENT_ESCAPE.containsMatchIn(text)) return text
            val bytes = java.io.ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '%' && i + 2 < text.length && PERCENT_ESCAPE.matches(text.substring(i, i + 3))) {
                    bytes.write(text.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    val count = Character.charCount(text.codePointAt(i))
                    val encoded = text.substring(i, i + count).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    i += count
                }
            }
            text = String(bytes.toByteArray(), Charsets.UTF_8)
        }
        return text
    }

    /**
     * The book ID in what the user typed: a bare ID (also grouped with spaces or dashes), a book page
     * link, or a share text containing one. Reader links (fanqienovel.com/reader/...) carry a chapter
     * ID, not a book ID, so they are rejected, and so is text with two different IDs.
     */
    fun extract(input: String): String? {
        val text = normalize(percentDecode(input)).trim()
        if (text.isEmpty()) return null
        if (isBookId(text)) return text
        PAGE_URL.find(text)?.let { return it.groupValues[1] }
        QUERY_PARAM.find(text)?.let { return it.groupValues[1] }
        if (text.contains("/reader/")) return null
        // Share texts like "《书名》… https://… 7143038691944959011"
        val ids = allIds(text)
        if (ids.isNotEmpty()) return ids.singleOrNull()
        return digitsOnly(text)?.takeIf(::isBookId)
    }

    /** Every distinct book-ID-like number in [input]. */
    fun allIds(input: String): List<String> =
        ANY_ID.findAll(normalize(percentDecode(input))).map { it.groupValues[1] }.distinct().toList()

    /**
     * The digits of [input] when it is only a number (optionally grouped with spaces, dashes or dots),
     * else null. "7668 2387 9662 5808 408" -> "7668238796625808408".
     */
    fun digitsOnly(input: String): String? {
        val text = normalize(input).trim()
        if (!GROUPED_NUMBER.matches(text)) return null
        return text.filter { it in '0'..'9' }
    }

    /**
     * True when [input] is clearly meant as an ID but isn't a valid one (a long number of the wrong
     * length, or a long number with a stray letter such as O for 0). Such input is never sent to the
     * title search: searching for numbers returns unrelated novels.
     */
    fun looksLikeBrokenId(input: String): Boolean {
        val text = normalize(input).trim()
        if (extract(text) != null) return false
        digitsOnly(text)?.let { return it.length >= 10 }
        val compact = text.filterNot { it == ' ' || it == '-' || it == '.' }
        if (compact.length < 12 || compact.any { it in '\u3400'..'\u9FFF' || it in '\uF900'..'\uFAFF' }) return false
        val digits = compact.count { it in '0'..'9' }
        return digits >= compact.length - 2
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
