package com.example.scraper.engine

import com.example.scraper.model.NextChapterCandidate
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** What the DOM script found on the current page, after Kotlin-side cleanup. */
data class ExtractionResult(
    val url: String,
    val canonicalUrl: String,
    val novelTitle: String,
    val chapterTitle: String,
    val paragraphs: List<String>,
    val characterCount: Int,
    val linkDensity: Double,
    val elementFound: Boolean,
    val isLoginWall: Boolean,
    val isChallenge: Boolean,
    val isErrorPage: Boolean,
    val contentSelector: String,
    val titleSelector: String,
    val nextSelector: String,
    val nextCandidates: List<NextChapterCandidate>,
    val atBottom: Boolean,
    val error: String? = null,
    /** Selector of a "Next" button that has no URL; clicked only when no link was found. */
    val nextClickSelector: String = ""
) {
    val paragraphCount: Int get() = paragraphs.size
    val contentHash: String by lazy { UrlIdentity.contentHash(paragraphs) }
    val chineseRatio: Double by lazy { UrlIdentity.cjkRatio(paragraphs.joinToString(" ")) }
    val hasChineseContent: Boolean get() = chineseRatio > UNTRANSLATED_RATIO
    val hasChineseTitle: Boolean get() = UrlIdentity.cjkRatio(chapterTitle) > 0.3
    /** Number parsed from the chapter title only (URL ids are not reliable for ordering). */
    val titleChapterNumber: Double? get() = UrlIdentity.chapterNumber(chapterTitle, null)
    /** Best-effort number from title or URL, only used as a navigation hint. */
    val navigationChapterNumber: Double? get() = UrlIdentity.chapterNumber(chapterTitle, url)

    companion object {
        /** Above this share of CJK characters the chapter is treated as not yet translated. */
        const val UNTRANSLATED_RATIO = 0.2
    }
}

object PageAnalyzer {

    /**
     * Decodes the value handed to `evaluateJavascript`'s callback. The scripts return
     * `JSON.stringify(...)`, so the callback receives a JSON string literal holding the JSON text.
     * Returns null when the page was navigating, the script threw, or the result is not an object.
     */
    fun parse(rawCallbackValue: String?, fallbackUrl: String): ExtractionResult? {
        val obj = decode(rawCallbackValue) ?: return null
        val error = obj.optString("error").takeIf { it.isNotBlank() }
        if (error != null && !obj.has("paragraphs")) return null

        val url = obj.optString("url").ifBlank { fallbackUrl }
        val chapterTitle = cleanTitle(obj.optString("chapterTitle"))
        val rawParagraphs = obj.optJSONArray("paragraphs").toStringList()
        val paragraphs = ChapterText.cleanParagraphs(rawParagraphs, chapterTitle)
        return ExtractionResult(
            url = url,
            canonicalUrl = obj.optString("canonicalUrl").ifBlank { url },
            novelTitle = cleanTitle(obj.optString("novelTitle")),
            chapterTitle = chapterTitle,
            paragraphs = paragraphs,
            characterCount = paragraphs.sumOf { it.length },
            linkDensity = obj.optDouble("linkDensity", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            elementFound = obj.optBoolean("elementFound", paragraphs.isNotEmpty()),
            isLoginWall = obj.optBoolean("isLoginWall", false),
            isChallenge = obj.optBoolean("isChallenge", false),
            isErrorPage = obj.optBoolean("isErrorPage", false),
            contentSelector = obj.optString("contentSelector"),
            titleSelector = obj.optString("titleSelector"),
            nextSelector = obj.optString("nextSelector"),
            nextCandidates = parseCandidates(obj.optJSONArray("nextCandidates")),
            atBottom = obj.optBoolean("atBottom", true),
            error = error,
            nextClickSelector = obj.optString("nextClickSelector")
        )
    }

    fun decode(rawCallbackValue: String?): JSONObject? {
        if (rawCallbackValue.isNullOrBlank() || rawCallbackValue == "null" || rawCallbackValue == "undefined") return null
        return try {
            when (val first = JSONTokener(rawCallbackValue).nextValue()) {
                is JSONObject -> first
                is String -> if (first.isBlank()) null else JSONTokener(first).nextValue() as? JSONObject
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes a plain JS string result (e.g. `document.title`). */
    fun decodeString(rawCallbackValue: String?): String? {
        if (rawCallbackValue.isNullOrBlank() || rawCallbackValue == "null") return null
        return try {
            JSONTokener(rawCallbackValue).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCandidates(array: JSONArray?): List<NextChapterCandidate> {
        if (array == null) return emptyList()
        val result = ArrayList<NextChapterCandidate>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank()) continue
            result += NextChapterCandidate(
                url = url,
                text = item.optString("text").trim(),
                score = item.optDouble("score", 0.0),
                reason = item.optString("reason"),
                selector = item.optString("selector")
            )
        }
        return result
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = opt(i)
            if (value is String) out += value
        }
        return out
    }

    fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('|', '-', '–', '—', '•', ':', ' ')
        .take(200)
}
