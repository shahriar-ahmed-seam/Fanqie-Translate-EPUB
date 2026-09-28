package com.example.scraper.engine

import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ValidationResult

/** Decides whether extracted content is a real chapter (not a menu, index, error or login page). */
object ChapterValidator {

    const val MAX_LINK_DENSITY = 0.35

    fun validate(result: ExtractionResult, settings: ScrapeSettings): ValidationResult {
        if (result.isErrorPage) return invalid("The page returned an error")
        if (result.isLoginWall) return invalid("The site asks you to log in")
        if (result.isChallenge) return invalid("The site is showing a verification check")
        if (result.paragraphs.isEmpty()) return invalid("No chapter text found on this page")
        if (result.characterCount < settings.minCharacterCount) {
            return invalid("Only ${result.characterCount} characters found (minimum ${settings.minCharacterCount})")
        }
        if (result.linkDensity > MAX_LINK_DENSITY) {
            return invalid("The text is mostly links (${(result.linkDensity * 100).toInt()}%), so this looks like a menu or chapter list")
        }
        if (result.paragraphCount < 2 && result.characterCount < 800) {
            return invalid("Only ${result.paragraphCount} paragraph found")
        }
        return ValidationResult(true, "${result.characterCount} characters in ${result.paragraphCount} paragraphs")
    }

    private fun invalid(message: String) = ValidationResult(false, message)
}
