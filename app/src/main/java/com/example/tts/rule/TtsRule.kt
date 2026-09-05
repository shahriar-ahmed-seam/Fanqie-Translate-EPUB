package com.example.tts.rule

import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Categorization of TTS speech rules.
 * SKIP: Omits the matched word/phrase/pattern completely from speech.
 * REPLACE: Substitutes the matched pattern with replacement/pronunciation text.
 */
enum class TtsRuleType {
    SKIP,
    SKIP_REGEX,
    REPLACE
}

/**
 * Result of rule validation.
 */
sealed class RuleValidationResult {
    object Valid : RuleValidationResult()
    data class Invalid(val reason: String) : RuleValidationResult()
}

/**
 * Domain model representing a custom rule applied strictly to spoken TTS audio.
 * Stored novel text, reader display text, database chapter text, and EPUB exports
 * are never affected by this rule.
 *
 * @param id Unique identifier.
 * @param name Optional label or description for user convenience.
 * @param ruleType SKIP (plain text), SKIP_REGEX (regular expression), or REPLACE (pronunciation).
 * @param pattern The text or regex to match.
 * @param replacement Replacement text for REPLACE rules (ignored for SKIP).
 * @param isRegex Whether [pattern] should be evaluated as a regular expression.
 * @param caseSensitive Whether matching should be case-sensitive.
 * @param wholeWord Whether matching should match whole words only (for plain text).
 * @param sortOrder User-defined priority/order within its category.
 * @param isEnabled Whether the rule is currently active.
 * @param bookId Optional book ID for book-scoped rules; null indicates a global rule.
 * @param createdAt Creation timestamp in milliseconds.
 */
data class TtsRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val ruleType: TtsRuleType = TtsRuleType.SKIP,
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val sortOrder: Int = 0,
    val isEnabled: Boolean = true,
    val bookId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Validates this rule's configuration.
     */
    fun validate(): RuleValidationResult {
        if (pattern.isBlank()) {
            return RuleValidationResult.Invalid("Rule pattern cannot be empty")
        }
        val isRegexRule = isRegex || ruleType == TtsRuleType.SKIP_REGEX
        if (isRegexRule) {
            try {
                Pattern.compile(pattern)
            } catch (e: PatternSyntaxException) {
                val desc = e.description?.ifBlank { "syntax error" } ?: "syntax error"
                return RuleValidationResult.Invalid("Invalid regex pattern: $desc")
            } catch (e: Throwable) {
                return RuleValidationResult.Invalid("Invalid regex: ${e.message}")
            }
        }
        return RuleValidationResult.Valid
    }
}
