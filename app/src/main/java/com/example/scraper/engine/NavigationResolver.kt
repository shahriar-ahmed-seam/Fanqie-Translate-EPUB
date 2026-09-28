package com.example.scraper.engine

import com.example.scraper.model.NextChapterCandidate

/** Picks the next-chapter link among the candidates the DOM script reported. */
object NavigationResolver {

    private val TOC_TEXT = Regex("(?i)\\b(index|contents|table of contents|toc|catalog|chapter list|all chapters)\\b|目录|目錄")
    private val PREV_TEXT = Regex("(?i)\\b(prev|previous)\\b|上一[章页頁节]")

    const val MIN_SCORE = 20.0

    fun selectNext(
        candidates: List<NextChapterCandidate>,
        currentUrl: String,
        currentChapterNumber: Double?,
        visitedKeys: Set<String>
    ): NextChapterCandidate? {
        val currentKey = UrlIdentity.normalize(currentUrl)
        return candidates.asSequence()
            .mapNotNull { candidate ->
                val key = UrlIdentity.normalize(candidate.url)
                if (key.isEmpty() || key == currentKey || key in visitedKeys) return@mapNotNull null
                if (!candidate.url.startsWith("http://") && !candidate.url.startsWith("https://")) return@mapNotNull null
                if (!UrlIdentity.isSameSite(currentUrl, candidate.url)) return@mapNotNull null
                if (TOC_TEXT.containsMatchIn(candidate.text) || PREV_TEXT.containsMatchIn(candidate.text)) return@mapNotNull null

                var score = candidate.score
                val targetNumber = UrlIdentity.chapterNumber(candidate.text, candidate.url)
                if (currentChapterNumber != null && targetNumber != null) {
                    val diff = targetNumber - currentChapterNumber
                    score += when {
                        diff in 0.9..1.1 -> 40.0
                        diff > 0.0 && diff < 1.0 -> 25.0
                        diff > 1.1 && diff <= 3.0 -> 5.0
                        diff > 3.0 -> -30.0
                        else -> -100.0
                    }
                }
                if (score > MIN_SCORE) candidate.copy(score = score) else null
            }
            .sortedByDescending { it.score }
            .firstOrNull()
    }

    private val EXPLICIT_CHAPTER_IN_URL = Regex("(?i)(?<=[/_-])((?:chapter|chap|ch|c|episode|ep)[-_]?)(\\d{1,5})(?=$|[/._?#-])")

    /**
     * Builds `…/chapter-13` from `…/chapter-12` when the page had no usable next link. Only URLs with
     * an explicit chapter marker are rewritten; bare numeric ids are too often unrelated to order.
     */
    fun synthesizeNext(currentUrl: String, currentChapterNumber: Double?): String? {
        val number = currentChapterNumber?.takeIf { it % 1.0 == 0.0 }?.toInt() ?: return null
        val path = currentUrl.substringBefore('?').substringBefore('#')
        val suffix = currentUrl.substring(path.length)
        val matches = EXPLICIT_CHAPTER_IN_URL.findAll(path).toList()
        val match = matches.lastOrNull { it.groupValues[2].toIntOrNull() == number } ?: return null
        val digits = match.groups[2] ?: return null
        val next = (number + 1).toString().padStart(digits.value.length, '0')
        return path.substring(0, digits.range.first) + next + path.substring(digits.range.last + 1) + suffix
    }
}
