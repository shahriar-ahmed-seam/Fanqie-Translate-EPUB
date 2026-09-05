package com.example.tts.rule

import java.util.regex.Matcher

/**
 * Dedicated, thread-safe processor for applying custom speech rules strictly to Android TTS audio.
 *
 * Guaranteed invariants:
 * 1. Deterministic Execution: Skip rules ALWAYS run before Replacement/pronunciation rules.
 *    Within each category, rules are ordered strictly by [TtsRule.sortOrder] ascending,
 *    then [TtsRule.createdAt] ascending, then [TtsRule.id].
 * 2. Scope: Global rules (bookId == null) run by default; book-specific rules run when matching the active novel.
 * 3. Zero Mutation: The original text passed to [process] is never mutated. Reader display text,
 *    stored database text, and EPUB exports remain completely unaffected.
 * 4. Safety: Regex compilation and evaluation are pre-validated, cached, and guarded by try/catch.
 *    Under no circumstances will a malformed rule, illegal regex, or parsing failure crash TTS.
 * 5. High Performance: Compiled Regex instances are cached in-memory. If no rules exist, [process]
 *    returns in O(1) time with zero allocations.
 */
class TtsTextProcessor {

    private data class CompiledRule(
        val rule: TtsRule,
        val regex: Regex
    ) {
        fun apply(text: String): String {
            return try {
                when (rule.ruleType) {
                    TtsRuleType.SKIP, TtsRuleType.SKIP_REGEX -> {
                        regex.replace(text, "")
                    }
                    TtsRuleType.REPLACE -> {
                        if (rule.isRegex) {
                            try {
                                regex.replace(text, rule.replacement)
                            } catch (e: Throwable) {
                                // Fall back to quoted literal if group backreferences fail
                                regex.replace(text, Matcher.quoteReplacement(rule.replacement))
                            }
                        } else {
                            regex.replace(text, Matcher.quoteReplacement(rule.replacement))
                        }
                    }
                }
            } catch (t: Throwable) {
                // Safeguard against any regex runtime error
                text
            }
        }
    }

    @Volatile
    private var compiledRules: List<CompiledRule> = emptyList()

    /**
     * Updates the active rules and pre-compiles regex patterns.
     */
    fun setRules(rules: List<TtsRule>) {
        val compiled = mutableListOf<CompiledRule>()
        for (rule in rules) {
            if (!rule.isEnabled || rule.pattern.isBlank()) continue
            val compiledRule = compileRule(rule)
            if (compiledRule != null) {
                compiled.add(compiledRule)
            }
        }
        this.compiledRules = compiled
    }

    /**
     * Transforms the given paragraph text into text ready for Android TTS audio playback.
     *
     * @param text Original paragraph text.
     * @param bookId Optional book ID to apply book-scoped rules alongside global rules.
     * @return Processed text for TTS, or original text if no rules apply or an error occurs.
     */
    fun process(text: String, bookId: String? = null): String {
        if (text.isBlank()) return ""
        val rules = compiledRules
        if (rules.isEmpty()) return text

        return try {
            processWithRules(text, rules, bookId)
        } catch (t: Throwable) {
            // Absolute safety guarantee: never crash TTS
            text
        }
    }

    companion object {
        /**
         * Compiles a [TtsRule] into a [CompiledRule].
         * Returns null if pattern is blank or invalid.
         */
        private fun compileRule(rule: TtsRule): CompiledRule? {
            if (rule.pattern.isBlank()) return null
            return try {
                val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val isRegexRule = rule.isRegex || rule.ruleType == TtsRuleType.SKIP_REGEX
                val regex = if (isRegexRule) {
                    Regex(rule.pattern, options)
                } else {
                    val prefix = if (rule.wholeWord && rule.pattern.firstOrNull()?.isLetterOrDigit() == true) "\\b" else ""
                    val suffix = if (rule.wholeWord && rule.pattern.lastOrNull()?.isLetterOrDigit() == true) "\\b" else ""
                    Regex("$prefix${Regex.escape(rule.pattern)}$suffix", options)
                }
                CompiledRule(rule, regex)
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * Cleans up spacing artifacts after removing words or phrases.
         * Collapses multiple spaces and fixes space before punctuation.
         */
        fun cleanSpacing(text: String): String {
            return text
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(" ,", ",")
                .replace(" .", ".")
                .replace(" !", "!")
                .replace(" ?", "?")
                .replace(" :", ":")
                .replace(" ;", ";")
                .trim()
        }

        /**
         * Core processing engine with deterministic ordering.
         */
        private fun processWithRules(
            text: String,
            rules: List<CompiledRule>,
            bookId: String? = null
        ): String {
            // Filter applicable rules: global (bookId == null) or matching current bookId
            val applicable = rules.filter {
                it.rule.bookId == null || (bookId != null && it.rule.bookId == bookId)
            }
            if (applicable.isEmpty()) return text

            // Partition into Skip and Replace (both SKIP and SKIP_REGEX run in phase 1)
            val skipRules = applicable.filter {
                it.rule.ruleType == TtsRuleType.SKIP || it.rule.ruleType == TtsRuleType.SKIP_REGEX
            }.sortedWith(compareBy<CompiledRule> { it.rule.sortOrder }
                .thenBy { it.rule.createdAt }
                .thenBy { it.rule.id })

            val replaceRules = applicable.filter { it.rule.ruleType == TtsRuleType.REPLACE }
                .sortedWith(compareBy<CompiledRule> { it.rule.sortOrder }
                    .thenBy { it.rule.createdAt }
                    .thenBy { it.rule.id })

            var currentText = text

            // 1. Apply Skip rules first in explicit order
            for (compiled in skipRules) {
                currentText = compiled.apply(currentText)
                if (currentText.isBlank()) {
                    return ""
                }
            }

            // 2. Apply Replacement/pronunciation rules in explicit order
            for (compiled in replaceRules) {
                currentText = compiled.apply(currentText)
            }

            // 3. Clean up spacing
            val cleaned = cleanSpacing(currentText)
            return if (cleaned.isBlank()) "" else cleaned
        }

        /**
         * Standalone test function useful for live UI previews and unit tests.
         */
        fun test(input: String, rules: List<TtsRule>, bookId: String? = null): String {
            if (input.isBlank()) return ""
            val compiled = rules
                .filter { it.isEnabled && it.pattern.isNotBlank() }
                .mapNotNull { compileRule(it) }
            if (compiled.isEmpty()) return input
            return try {
                processWithRules(input, compiled, bookId)
            } catch (t: Throwable) {
                input
            }
        }
    }
}
