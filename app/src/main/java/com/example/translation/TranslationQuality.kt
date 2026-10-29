package com.example.translation

/**
 * Decides whether machine-translated text can be used. Shared by the provider (is an answer worth
 * keeping?), the queue (accept, repair or retry a chunk), the start-up repair of old translations,
 * and the reader/export (never show junk).
 *
 * The problems it catches are the ones seen in real books:
 * - `[""]`: Google's empty answer, which used to be stored as the translation of whole chapters;
 * - "…zh-CN": language codes glued to the text by the fallback endpoint's old parser;
 * - Chinese left in the output: whole paragraphs or sentences Google sent back untranslated,
 *   and chapter titles returned as they were.
 */
object TranslationQuality {

    /** Paragraphs are separated like this in chunk texts (see EpubChunker). */
    const val PARAGRAPH_SEPARATOR = "\n\n"
    private val PARAGRAPH_SPLIT = Regex("\n\n+")

    /** Fewest Han characters before a body paragraph can count as untranslated. */
    private const val MIN_HAN_IN_PARAGRAPH = 4
    /** Han share of (Han + Latin letters) from which a paragraph counts as untranslated. */
    private const val HAN_SHARE_UNTRANSLATED = 0.3
    /** An unbroken run of this many Han characters is an untranslated sentence inside English text. */
    private const val UNTRANSLATED_RUN = 6
    /** Titles are short: this many Han characters left in one means it wasn't translated. */
    private const val MIN_HAN_IN_TITLE = 2
    /** Sources up to this long (titles, short lines) are judged like titles by [looksTranslated]. */
    private const val SHORT_TEXT_CHARS = 40
    /** Titles at least this Chinese (Han share of Han + Latin letters) aren't shown as translations. */
    private const val TITLE_HAN_SHARE_HIDDEN = 0.5

    private val LANGUAGE_CODE_TAIL = Regex("""(?:zh-CN|zh-TW)(?:zh-CN|zh-TW|\d+(?:\.\d+)?)*$""")
    private val JSON_ESCAPE = Regex("""\\u[0-9a-fA-F]{4}|\\[nrt"\\/]""")
    private val JSON_JUNK_WORDS = Regex("""null|zh-CN|zh-TW|true|false""")

    fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun joinParagraphs(paragraphs: List<String>): String = paragraphs.joinToString(PARAGRAPH_SEPARATOR)

    fun isHan(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F

    fun hanCount(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isHan(cp)) count++
            i += Character.charCount(cp)
        }
        return count
    }

    private fun latinLetterCount(text: String): Int = text.count { it.isLetter() && it.code < 0x0250 }

    private fun longestHanRun(text: String): Int {
        var longest = 0
        var current = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isHan(cp)) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
            i += Character.charCount(cp)
        }
        return longest
    }

    /**
     * True for text that is only JSON syntax around blank values (`[""]`, `["　　"]`, `[null,"zh-CN"]`):
     * a raw engine answer, not a translation. A real sentence in brackets and quotes is not junk.
     */
    fun isJsonJunk(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || !t.startsWith("[") || !t.endsWith("]")) return false
        val rest = JSON_JUNK_WORDS.replace(JSON_ESCAPE.replace(t, ""), "")
            .filterNot { it == '[' || it == ']' || it == ',' || it == '"' || it == '.' || it.isDigit() || it.isWhitespace() || it == '\u3000' || it == '\u00A0' }
        return rest.isEmpty()
    }

    /** Language codes the old fallback parser glued to the end of a translation ("…Empty Housezh-CN"). */
    fun hasLanguageCodeTail(source: String?, translated: String): Boolean {
        val tail = LANGUAGE_CODE_TAIL.find(translated.trim()) ?: return false
        return source == null || !source.trimEnd().endsWith(tail.value)
    }

    /**
     * The translation without junk, or null when nothing usable is left: blank, raw `[""]`-style
     * answers, or only language codes. Used wherever stored translations are shown or exported.
     */
    fun usable(translated: String?, source: String? = null): String? {
        if (translated == null) return null
        var text = translated.trim()
        if (text.isEmpty() || isJsonJunk(text)) return null
        if (hasLanguageCodeTail(source, text)) {
            text = LANGUAGE_CODE_TAIL.replace(text, "").trim()
            if (text.isEmpty()) return null
        }
        return text
    }

    /**
     * A chapter or book title fit to show: usable, not the Chinese sent back, and not mostly Chinese.
     * A partly translated title ("Chapter 1 空屋") is shown as it is, as before; new translations
     * are checked more strictly (see [isUntranslated]) and the rest is translated again.
     */
    fun usableTitle(translated: String?, source: String? = null): String? {
        val text = usable(translated, source) ?: return null
        if (source != null && needsTranslation(source) && isEcho(source, text)) return null
        val han = hanCount(text)
        if (han == 0) return text
        return text.takeUnless { han.toDouble() / (han + latinLetterCount(text)) >= TITLE_HAN_SHARE_HIDDEN }
    }

    /** True when [source] has Chinese that needs translating (pure symbols, numbers or English don't). */
    fun needsTranslation(source: String): Boolean = hanCount(source) > 0

    /**
     * True when [translated] is Chinese left as it was: the source sent back (echo), mostly Chinese, or
     * an untranslated sentence inside the English text. Only applies when the source has Chinese.
     */
    fun isUntranslated(source: String, translated: String, isTitle: Boolean = false): Boolean {
        if (!needsTranslation(source)) return false
        if (hanCount(translated) == 0) return false
        if (isEcho(source, translated) && hanCount(source) >= MIN_HAN_IN_TITLE) return true
        return leftInChinese(translated, isTitle)
    }

    /** True when translated text still has a lot of Chinese (whatever its source was). */
    fun leftInChinese(translated: String, isTitle: Boolean = false): Boolean {
        val han = hanCount(translated)
        if (han == 0) return false
        if (isTitle) return han >= MIN_HAN_IN_TITLE
        if (longestHanRun(translated) >= UNTRANSLATED_RUN) return true
        if (han < MIN_HAN_IN_PARAGRAPH) return false
        return han.toDouble() / (han + latinLetterCount(translated)) >= HAN_SHARE_UNTRANSLATED
    }

    /** True when one paragraph's (or a title's) translation can't be kept. */
    fun isBad(source: String, translated: String?, isTitle: Boolean = false): Boolean {
        if (!needsTranslation(source)) return false
        val text = usable(translated, source) ?: return true
        return isUntranslated(source, text, isTitle)
    }

    /**
     * Quick check of a whole answer before paragraph checks: false when it's empty or junk, or the
     * Chinese came back (mostly) unchanged. Used to decide whether to ask the fallback endpoint.
     */
    fun looksTranslated(source: String, translated: String?): Boolean {
        if (!needsTranslation(source)) return true
        val text = usable(translated, source) ?: return false
        if (isEcho(source, text)) return false
        val han = hanCount(text)
        if (han == 0) return true
        // Titles and short lines: any real Chinese left means it wasn't translated.
        if (source.length <= SHORT_TEXT_CHARS) return han < MIN_HAN_IN_TITLE
        if (longestHanRun(text) >= UNTRANSLATED_RUN) return false
        if (han < MIN_HAN_IN_PARAGRAPH) return true
        return han.toDouble() / (han + latinLetterCount(text)) < HAN_SHARE_UNTRANSLATED
    }

    /** True when [translated] is [source] sent back unchanged. */
    fun isEcho(source: String, translated: String): Boolean = squash(translated) == squash(source)

    /** Result of checking a translated chunk paragraph by paragraph. */
    data class Report(
        val sourceParagraphs: List<String>,
        val translatedParagraphs: List<String>,
        /** Indices (into the source paragraphs) of bad translations; only when the counts match. */
        val badIndices: List<Int>,
        /** True when the translation has a different number of paragraphs than the source. */
        val countMismatch: Boolean,
        /** Translated paragraphs left in Chinese when the counts don't match (can't be paired). */
        val badUnpaired: Int
    ) {
        val isClean: Boolean get() = badIndices.isEmpty() && badUnpaired == 0
        val badCount: Int get() = if (countMismatch) badUnpaired else badIndices.size
    }

    /** Checks a body/description chunk's translation against its source, paragraph by paragraph. */
    fun check(sourceText: String, translatedText: String): Report {
        val source = splitParagraphs(sourceText)
        val cleaned = usable(translatedText, sourceText)
        val translated = if (cleaned == null) emptyList() else splitParagraphs(cleaned)
        if (translated.size == source.size) {
            val bad = source.indices.filter { isBad(source[it], translated[it]) }
            return Report(source, translated, bad, countMismatch = false, badUnpaired = 0)
        }
        // Google merged or split paragraphs: they can't be paired, so judge the text alone.
        val badUnpaired = if (translated.isEmpty()) {
            source.count { needsTranslation(it) }.coerceAtLeast(1)
        } else {
            translated.count { leftInChinese(it) }
        }
        return Report(source, translated, emptyList(), countMismatch = true, badUnpaired = badUnpaired)
    }

    private fun squash(text: String): String = text.filterNot { it.isWhitespace() || it == '\u3000' }
}
