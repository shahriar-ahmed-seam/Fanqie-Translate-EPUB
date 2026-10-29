package com.example.tomato

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

/** What Fanqie's public website says about a book ID. */
sealed interface BookPageResult {
    data class Found(val book: TomatoBook) : BookPageResult
    data object NotFound : BookPageResult
    data class Failed(val message: String) : BookPageResult
}

/** Looks a book up on Fanqie's website (replaced by a fake in tests). */
fun interface TomatoBookPageSource {
    suspend fun fetch(bookId: String): BookPageResult
}

/**
 * Reads a book's public page, https://fanqienovel.com/page/{id}. The lookup uses it when the engine
 * can't answer (still starting, a network hiccup, an engine-side error), so a valid ID isn't reported
 * as unknown. Downloads still go through the engine.
 */
class FanqieBookPage(private val client: OkHttpClient) : TomatoBookPageSource {

    override suspend fun fetch(bookId: String): BookPageResult = withContext(Dispatchers.IO) {
        if (!TomatoIds.isBookId(bookId)) return@withContext BookPageResult.NotFound
        val request = Request.Builder()
            .url("$PAGE_URL$bookId")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val book = parse(html, bookId)
                when {
                    book != null -> BookPageResult.Found(book)
                    // Unknown IDs get a 404 page (with an empty book in its data).
                    response.code == 404 -> BookPageResult.NotFound
                    response.isSuccessful && html.contains(STATE_MARKER) -> BookPageResult.NotFound
                    else -> BookPageResult.Failed("Fanqie's website answered with HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            BookPageResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "no connection")
        } catch (e: IllegalArgumentException) {
            BookPageResult.Failed(e.message ?: "bad address")
        }
    }

    companion object {
        private const val PAGE_URL = "https://fanqienovel.com/page/"
        private const val STATE_MARKER = "window.__INITIAL_STATE__"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** The book in a page's `window.__INITIAL_STATE__.page`, or null if the page doesn't describe [expectedId]. */
        fun parse(html: String, expectedId: String): TomatoBook? {
            val marker = html.indexOf(STATE_MARKER)
            if (marker < 0) return null
            val start = html.indexOf('{', marker)
            if (start < 0) return null
            val page = try {
                // The state is a JavaScript object literal: JSON plus bare `undefined` values.
                val state = JSONTokener(html.substring(start).replace(":undefined", ":null")).nextValue() as? JSONObject
                state?.optJSONObject("page")
            } catch (e: Exception) {
                null
            } ?: return null
            val id = page.opt("bookId")?.toString()?.trim().orEmpty()
            if (id != expectedId) return null
            val title = page.optString("bookName").trim()
            if (title.isEmpty()) return null
            return TomatoBook(
                bookId = id,
                title = title,
                author = page.optString("author").ifBlank { page.optString("authorName") }.trim(),
                abstractText = page.optString("abstract").ifBlank { page.optString("description") }.trim(),
                coverUrl = page.optString("thumbUrl").ifBlank { page.optString("thumbUri") }.trim()
                    .takeIf { it.startsWith("http") }.orEmpty(),
                category = categoryName(page.opt("categoryV2")),
                serialStatus = when (page.opt("creationStatus")?.toString()?.trim()) {
                    "0" -> "已完结"
                    "1" -> "连载中"
                    else -> ""
                },
                wordCount = page.opt("wordNumber").asLong(),
                chapterCount = page.opt("chapterTotal").asLong().toInt(),
                readCount = page.opt("readCount").asLong(),
                lastChapterTime = page.opt("lastPublishTime").asLong()
            )
        }

        /** categoryV2 is a JSON array (usually sent as a string) of {Name, Dim, MainCategory}. */
        private fun categoryName(raw: Any?): String {
            val array = when (raw) {
                is JSONArray -> raw
                is String -> if (raw.trim().startsWith("[")) {
                    try {
                        JSONArray(raw)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                else -> null
            } ?: return ""
            val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val main = entries.firstOrNull { it.optBoolean("MainCategory") }
                ?: entries.firstOrNull { it.optInt("Dim") == 10 }
                ?: entries.firstOrNull()
            return main?.optString("Name")?.trim().orEmpty()
        }

        private fun Any?.asLong(): Long = when (this) {
            is Number -> toLong()
            is String -> trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
