package com.example.scraper.engine

/**
 * Chapter text is stored as plain paragraphs extracted from the rendered page, so no markup from the
 * website ever reaches an EPUB. This object filters boilerplate and renders safe XHTML.
 */
object ChapterText {

    private val NAV_LINES = Regex(
        "(?i)^(previous|prev|next|back|index|home|menu|report|bookmark|comments?|share|settings|" +
            "table of contents|contents|chapter list|previous chapter|next chapter|prev chapter|" +
            "上一章|下一章|目录|书签|返回)[\\s:：.!]*$"
    )

    private val AD_PHRASES = listOf(
        "join discord", "join our discord", "support us on patreon", "support me on patreon",
        "read latest chapters at", "read the latest chapter", "visit our website", "turn on notifications",
        "bookmark this page", "please disable your adblock", "please support the translator",
        "if you find any errors", "advertisement", "sponsored content"
    )

    /** Removes empty/duplicate-boilerplate lines, navigation labels and a leading copy of the title. */
    fun cleanParagraphs(raw: List<String>, chapterTitle: String = ""): List<String> {
        val titleKey = chapterTitle.normalizedKey()
        val out = ArrayList<String>(raw.size)
        for (line in raw) {
            val text = line.replace('\u00A0', ' ').replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim()
            if (text.isEmpty()) continue
            if (isBoilerplate(text)) continue
            if (out.isEmpty() && titleKey.isNotEmpty() && text.normalizedKey() == titleKey) continue
            out += text
        }
        return out
    }

    fun isBoilerplate(text: String): Boolean {
        if (text.length <= 40 && NAV_LINES.matches(text)) return true
        val lower = text.lowercase()
        if (text.length <= 160 && AD_PHRASES.any { lower.contains(it) }) return true
        // Lines made only of arrows/separators ("«", "|", "→") and "« Prev | Next »" bars are navigation chrome.
        if (text.length <= 20 && ARROWS_ONLY.matches(text)) return true
        if (text.length <= 60 && PREV_NEXT_BAR.matches(text)) return true
        return false
    }

    private val ARROWS_ONLY = Regex("^[\\s«»‹›<>←→|/·•]+$")
    private val PREV_NEXT_BAR = Regex("(?i)^[«‹<←\\s]*(prev|previous)\\b.*\\bnext\\b[\\s›»>→]*$")

    private fun String.normalizedKey(): String = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** `<p>` elements for an XHTML body. Every paragraph is escaped. */
    fun toXhtmlParagraphs(paragraphs: List<String>): String =
        paragraphs.joinToString("\n") { "<p>${escapeXml(it)}</p>" }

    /** Paragraph list back from the stored representation. */
    fun fromStored(stored: String): List<String> {
        if (stored.isBlank()) return emptyList()
        return Regex("(?s)<p>(.*?)</p>").findAll(stored)
            .map { unescapeXml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()
            .ifEmpty { stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() } }
    }

    fun escapeXml(text: String): String {
        val clean = stripInvalidXmlChars(text)
        val sb = StringBuilder(clean.length + 16)
        for (ch in clean) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Drops characters that are not allowed in XML 1.0 (control chars, lone surrogates, U+FFFE/F). */
    fun stripInvalidXmlChars(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        sb.append(ch).append(text[i + 1])
                        i++
                    }
                }
                Character.isLowSurrogate(ch) -> Unit
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' -> Unit
                ch == '\uFFFE' || ch == '\uFFFF' -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
