package com.example.queue

import android.util.Log
import com.example.translation.TranslationException
import com.example.translation.TranslationProvider
import com.example.translation.TranslationQuality
import com.example.translation.TranslationQualityException

/**
 * Translates one chunk and only returns translations that can be kept:
 * - nothing empty or junk (`[""]`) is ever returned as a translation;
 * - paragraphs that came back in Chinese are re-translated one by one and put back in place;
 * - Chinese words left inside English sentences (Google sometimes keeps names, signs or
 *   exclamations) are translated on their own, in one request, and put back in place;
 * - a chapter title still in Chinese counts as a failure.
 * Anything else becomes a soft failure, which the queue retries after a pause.
 *
 * Each attempt makes a limited number of extra requests, so one stubborn chunk can't flood Google.
 */
class ChunkTranslator(private val provider: () -> TranslationProvider) {

    /** Extra requests one attempt may still make (the first, whole-chunk request isn't counted). */
    private class Budget(var left: Int) {
        fun take(): Boolean = if (left > 0) { left--; true } else false
    }

    /**
     * Translates [sourceText]. With [thorough] (used for later attempts), a chunk Google keeps
     * answering empty or in Chinese is translated paragraph by paragraph instead.
     */
    suspend fun translate(chunkType: String, sourceText: String, thorough: Boolean = false): Result<String> {
        if (!TranslationQuality.needsTranslation(sourceText)) return Result.success(sourceText)
        val budget = Budget(if (thorough) THOROUGH_EXTRA_REQUESTS else EXTRA_REQUESTS)
        val raw = provider().translate(sourceText, SOURCE_LANG, TARGET_LANG)
        val error = raw.exceptionOrNull()
        if (error != null) {
            val soft = (error as? TranslationException)?.isSoftFailure == true
            return if (thorough && soft && !isTitle(chunkType)) paragraphByParagraph(sourceText, budget) else Result.failure(error)
        }
        val translated = raw.getOrThrow()
        return if (isTitle(chunkType)) checkTitle(sourceText, translated, budget) else repairParagraphs(sourceText, translated, budget, thorough)
    }

    /**
     * Translates again a chunk whose saved translation has Chinese left (books translated by
     * earlier versions). Only what is still Chinese is translated again: paragraphs left in Chinese
     * and Chinese words inside English sentences. Unusable or mostly Chinese text is translated anew.
     */
    suspend fun retranslate(chunkType: String, sourceText: String, stored: String): Result<String> {
        if (!TranslationQuality.needsTranslation(sourceText)) return Result.success(sourceText)
        val text = TranslationQuality.usable(stored, sourceText) ?: return translate(chunkType, sourceText)
        val budget = Budget(EXTRA_REQUESTS)
        if (isTitle(chunkType)) {
            if (TranslationQuality.hanCount(text) == 0) return Result.success(text)
            val fixed = checkTitle(sourceText, text, budget)
            return if (fixed.isSuccess) fixed else translate(chunkType, sourceText)
        }
        val report = TranslationQuality.check(sourceText, text)
        if (report.isClean && TranslationQuality.hanCount(text) == 0) return Result.success(text)
        if (needsFullRetranslation(report)) return translate(chunkType, sourceText)
        return repairParagraphs(sourceText, text, budget, thorough = false)
    }

    private suspend fun checkTitle(source: String, translated: String, budget: Budget): Result<String> {
        val text = TranslationQuality.usable(translated, source)
        var best: String? = null
        if (text != null && !TranslationQuality.isEcho(source, text)) {
            // "Chapter 1 空屋": the Chinese part is translated on its own.
            val polished = if (TranslationQuality.hanCount(text) > 0) polish(listOf(text), budget).single() else text
            if (!TranslationQuality.isUntranslated(source, polished, isTitle = true)) return Result.success(polished)
            best = polished.takeIf { TranslationQuality.hanCount(it) < TranslationQuality.hanCount(source) }
        }
        return Result.failure(
            TranslationQualityException(
                message = "The title came back untranslated",
                bestText = best,
                badParagraphs = 1,
                totalParagraphs = 1
            )
        )
    }

    private suspend fun repairParagraphs(sourceText: String, translated: String, budget: Budget, thorough: Boolean): Result<String> {
        val report = TranslationQuality.check(sourceText, translated)
        val source = report.sourceParagraphs
        val total = source.size
        val cleaned = TranslationQuality.usable(translated, sourceText)
        if (cleaned == null || needsFullRetranslation(report)) {
            // Empty (`[""]`) or mostly Chinese: the whole answer was bad. Retry the chunk later, or
            // on later attempts translate it paragraph by paragraph.
            return if (thorough) paragraphByParagraph(sourceText, budget) else Result.failure(qualityError(report.badCount, total, best = null))
        }
        if (report.isClean && TranslationQuality.hanCount(cleaned) == 0) return Result.success(cleaned)

        // 1. Paragraphs that came back in Chinese are translated again one by one (when the answer
        //    has the same paragraphs as the source).
        val paragraphs = report.translatedParagraphs.toMutableList()
        if (!report.countMismatch) {
            for (index in report.badIndices) {
                if (!budget.take()) break
                val retried = translateParagraph(source[index]).getOrElse { return Result.failure(it) }
                if (retried != null && TranslationQuality.hanCount(retried) < TranslationQuality.hanCount(paragraphs[index])) {
                    paragraphs[index] = retried
                }
            }
        }

        // 2. Chinese words left inside the English text are translated on their own.
        var joined = TranslationQuality.joinParagraphs(polish(paragraphs, budget))
        var after = TranslationQuality.check(sourceText, joined)

        // 3. Google merged or split paragraphs and Chinese is still left: translate each source
        //    paragraph on its own (when the request budget allows).
        if (!after.isClean && report.countMismatch) {
            val rebuilt = translateEach(source, budget).getOrElse { return Result.failure(it) }
            if (rebuilt != null) {
                val candidate = TranslationQuality.joinParagraphs(polish(rebuilt, budget))
                val candidateReport = TranslationQuality.check(sourceText, candidate)
                if (candidateReport.badCount <= after.badCount) {
                    joined = candidate
                    after = candidateReport
                }
            }
        }

        if (after.isClean) {
            if (!report.isClean) Log.i(TAG, "Repaired a chunk of $total paragraphs that came back partly untranslated")
            return Result.success(joined)
        }
        return Result.failure(qualityError(after.badCount, total, best = joined))
    }

    /** The chunk translated one paragraph at a time (for chunks Google won't translate whole). */
    private suspend fun paragraphByParagraph(sourceText: String, budget: Budget): Result<String> {
        val source = TranslationQuality.splitParagraphs(sourceText)
        val translated = translateEach(source, budget).getOrElse { return Result.failure(it) }
            ?: return Result.failure(qualityError(source.size, source.size, best = null))
        val joined = TranslationQuality.joinParagraphs(polish(translated, budget))
        val report = TranslationQuality.check(sourceText, joined)
        if (report.isClean) {
            Log.i(TAG, "Translated a chunk of ${source.size} paragraphs one by one")
            return Result.success(joined)
        }
        return Result.failure(qualityError(report.badCount, source.size, best = joined))
    }

    /**
     * Each paragraph translated on its own (untranslatable ones stay as they are); null when the
     * request budget is too small for all of them.
     */
    private suspend fun translateEach(source: List<String>, budget: Budget): Result<List<String>?> {
        val needed = source.count { TranslationQuality.needsTranslation(it) }
        if (needed > budget.left) return Result.success(null)
        val result = source.map { paragraph ->
            if (!TranslationQuality.needsTranslation(paragraph) || !budget.take()) {
                paragraph
            } else {
                translateParagraph(paragraph).getOrElse { return Result.failure(it) } ?: paragraph
            }
        }
        return Result.success(result)
    }

    /**
     * One paragraph translated on its own: the translation, or null when it came back unusable.
     * Rate limits and blocks are failures, so the caller stops (the queue waits them out).
     */
    private suspend fun translateParagraph(paragraph: String): Result<String?> {
        val answer = provider().translate(paragraph, SOURCE_LANG, TARGET_LANG)
        val error = answer.exceptionOrNull()
        if (error is TranslationException && stopsRepairs(error)) return Result.failure(error)
        val text = answer.getOrNull()?.let { TranslationQuality.usable(it, paragraph) }
        return Result.success(text?.takeUnless { TranslationQuality.isEcho(paragraph, it) })
    }

    /**
     * Translates the Chinese words left inside otherwise English [paragraphs] on their own (all in
     * one request) and puts the translations in their place. Best effort: on any problem the
     * paragraphs come back unchanged.
     */
    private suspend fun polish(paragraphs: List<String>, budget: Budget): List<String> {
        val segments = paragraphs.asSequence()
            .flatMap { LEFTOVER.findAll(it).map { match -> match.value } }
            .distinct()
            .take(MAX_POLISH_SEGMENTS)
            .toList()
        if (segments.isEmpty() || !budget.take()) return paragraphs
        val translations = translateSegments(segments, budget)
        if (translations.isEmpty()) return paragraphs
        return paragraphs.map { paragraph ->
            LEFTOVER.replace(paragraph) { match ->
                translations[match.value]?.let { spaced(paragraph, match.range, it) } ?: match.value
            }
        }
    }

    private suspend fun translateSegments(segments: List<String>, budget: Budget): Map<String, String> {
        val answer = provider().translate(segments.joinToString(TranslationQuality.PARAGRAPH_SEPARATOR), SOURCE_LANG, TARGET_LANG)
        val error = answer.exceptionOrNull()
        if (error is TranslationException && stopsRepairs(error)) return emptyMap()
        val parts = answer.getOrNull()?.let { TranslationQuality.usable(it) }?.let(TranslationQuality::splitParagraphs)
        val pairs: List<Pair<String, String?>> = if (parts != null && parts.size == segments.size) {
            segments.zip(parts)
        } else {
            // The answer's parts can't be paired with the words: ask for a few of them one by one.
            segments.take(MAX_SINGLE_SEGMENT_REQUESTS).mapNotNull { segment ->
                if (!budget.take()) return@mapNotNull null
                segment to provider().translate(segment, SOURCE_LANG, TARGET_LANG).getOrNull()
                    ?.let { TranslationQuality.usable(it, segment) }
            }
        }
        return pairs.mapNotNull { (segment, translated) ->
            val text = translated?.replace(Regex("\\s+"), " ")?.trim()
            if (text.isNullOrEmpty() || TranslationQuality.hanCount(text) > 0) null else segment to text
        }.toMap()
    }

    /** [replacement] with the spaces English needs around it where it replaces [range] of [text]. */
    private fun spaced(text: String, range: IntRange, replacement: String): String {
        var result = replacement
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        if (before != null && (before.isLetterOrDigit() || before in ",.;:!?)")) result = " $result"
        if (after != null && after.isLetterOrDigit()) result = "$result "
        return result
    }

    private fun stopsRepairs(error: TranslationException) = error.isRateLimited || error.isPermanent || error.retryAfterMs != null

    private fun qualityError(bad: Int, total: Int, best: String?) = TranslationQualityException(
        message = "$bad of $total paragraphs came back empty or untranslated",
        bestText = best,
        badParagraphs = bad,
        totalParagraphs = total
    )

    companion object {
        private const val TAG = "ChunkTranslator"
        private const val SOURCE_LANG = "zh"
        private const val TARGET_LANG = "en"
        /** Extra requests per normal attempt (paragraph repairs and leftover words). */
        private const val EXTRA_REQUESTS = 30
        /** Extra requests per thorough attempt (a chunk translated paragraph by paragraph). */
        private const val THOROUGH_EXTRA_REQUESTS = 90
        /** Most leftover Chinese words translated per chunk. */
        private const val MAX_POLISH_SEGMENTS = 60
        private const val MAX_SINGLE_SEGMENT_REQUESTS = 6

        private const val HAN = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"
        /** A run of Chinese characters, with the Chinese punctuation right after it ("跳？"). */
        private val LEFTOVER = Regex("[$HAN][$HAN\\u3000-\\u303F\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF5E\\u2026\\u00B7]*")

        fun isTitle(chunkType: String) = chunkType == "TITLE" || chunkType == "CHAPTER_TITLE"

        /** More bad paragraphs than this means the whole answer was bad. */
        private fun maxRepairs(total: Int): Int = maxOf(3, total / 3)

        /** True when a translation is so bad that translating the whole chunk again is the fix. */
        fun needsFullRetranslation(report: TranslationQuality.Report): Boolean {
            if (report.countMismatch && report.translatedParagraphs.isEmpty()) return true
            return report.badCount > maxRepairs(report.sourceParagraphs.size)
        }

        /**
         * Whether the best partial translation may be kept when a chunk has no retries left: every
         * paragraph is present and only a few (at most one in ten, at least one) are still Chinese.
         * Keeping it shows the reader one Chinese line instead of a missing chunk.
         */
        fun isAcceptableLastResort(error: TranslationQualityException): Boolean {
            val best = error.bestText ?: return false
            if (best.isBlank() || TranslationQuality.isJsonJunk(best)) return false
            return error.badParagraphs <= maxOf(1, error.totalParagraphs / 10)
        }
    }
}
