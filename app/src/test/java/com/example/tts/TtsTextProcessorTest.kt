package com.example.tts

import com.example.data.db.toEntity
import com.example.data.db.toModel
import com.example.tts.rule.RuleValidationResult
import com.example.tts.rule.TtsRule
import com.example.tts.rule.TtsRuleType
import com.example.tts.rule.TtsTextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure Kotlin unit tests for [TtsTextProcessor] and [TtsRule].
 * Tests all skip, regex, replacement, deterministic ordering, scope, and safety requirements.
 * Free of Robolectric or Android framework dependencies.
 */
class TtsTextProcessorTest {

    private lateinit var processor: TtsTextProcessor

    @Before
    fun setUp() {
        processor = TtsTextProcessor()
    }

    @Test
    fun `no rules returns original text unmodified`() {
        val original = "The quick brown fox jumps over the lazy dog."
        assertEquals(original, processor.process(original))
    }

    @Test
    fun `skip word exact case-insensitive`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            caseSensitive = false,
            wholeWord = true
        )
        processor.setRules(listOf(rule))

        val input = "I ate a tomato for lunch, and another Tomato for dinner."
        val expected = "I ate a for lunch, and another for dinner."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `skip word case-sensitive respects casing`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            caseSensitive = true,
            wholeWord = true
        )
        processor.setRules(listOf(rule))

        val input = "The Tomato was red, but the tomato plant was green."
        val expected = "The was red, but the tomato plant was green."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `skip word whole-word matching does not affect substrings`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            wholeWord = true,
            caseSensitive = false
        )
        processor.setRules(listOf(rule))

        val input = "Tomatoes are great, but this CherryTomato and this Tomato are different."
        val expected = "Tomatoes are great, but this CherryTomato and this are different."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `skip regex removes matching patterns`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP_REGEX,
            pattern = "\\[.*?\\]"
        )
        processor.setRules(listOf(rule))

        val input = "Chapter 42 [Author Note: Thanks for reading!] The battle began."
        val expected = "Chapter 42 The battle began."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `invalid regex never crashes and preserves text`() {
        val invalidRule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "[unclosed bracket",
            isRegex = true
        )
        // Should compile without throwing exception
        processor.setRules(listOf(invalidRule))

        val input = "Some novel text here."
        assertEquals(input, processor.process(input))
    }

    @Test
    fun `replacement rule substitutes pronunciation text`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "Tom",
            replacement = "Jack",
            wholeWord = true
        )
        processor.setRules(listOf(rule))

        val input = "Tom said to Tomorrow that Tom was ready."
        // With wholeWord=true, "Tomorrow" should remain untouched
        val expected = "Jack said to Tomorrow that Jack was ready."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `replacement rule safely handles special regex characters in replacement`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "cost",
            replacement = "$100 \\ 50%",
            wholeWord = true
        )
        processor.setRules(listOf(rule))

        val input = "The total cost is high."
        val expected = "The total $100 \\ 50% is high."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `deterministic ordering applies skip rules before replacement rules`() {
        // If replace ran first: "Tomato" -> "Red Fruit" -> skip rule wouldn't match
        // Because skips run first: "Tomato" is skipped first
        val skipRule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            wholeWord = true,
            sortOrder = 10
        )
        val replaceRule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "Tom",
            replacement = "Jerry",
            wholeWord = true,
            sortOrder = 1 // Lower sortOrder than skip, but Skip category must run first!
        )
        processor.setRules(listOf(replaceRule, skipRule))

        val input = "Tom bought a Tomato today."
        val expected = "Jerry bought a today."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `deterministic ordering respects sortOrder within category`() {
        val replace1 = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "A",
            replacement = "B",
            sortOrder = 1
        )
        val replace2 = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "B",
            replacement = "C",
            sortOrder = 2
        )
        processor.setRules(listOf(replace2, replace1)) // Insert out of order

        val input = "A"
        // 1 executes: A -> B, then 2 executes: B -> C
        assertEquals("C", processor.process(input))
    }

    @Test
    fun `scope filters global vs book-specific rules`() {
        val globalRule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "Qi",
            replacement = "Chee",
            bookId = null // Global
        )
        val bookSpecificRule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "Master",
            replacement = "Teacher",
            bookId = "book_123"
        )
        processor.setRules(listOf(globalRule, bookSpecificRule))

        val input = "Master condensed his Qi."

        // Reading book_123: both rules apply
        val resultBook123 = processor.process(input, bookId = "book_123")
        assertEquals("Teacher condensed his Chee.", resultBook123)

        // Reading another book: only global rule applies
        val resultOtherBook = processor.process(input, bookId = "book_456")
        assertEquals("Master condensed his Chee.", resultOtherBook)
    }

    @Test
    fun `complete paragraph skipped returns blank`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "\\[.*?\\]",
            isRegex = true
        )
        processor.setRules(listOf(rule))

        val input = "[Chapter 1: The Beginning]"
        assertEquals("", processor.process(input))
    }

    @Test
    fun `disabled rules are not applied`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            isEnabled = false
        )
        processor.setRules(listOf(rule))

        val input = "A ripe Tomato."
        assertEquals(input, processor.process(input))
    }

    @Test
    fun `rule validation detects empty patterns and invalid regex`() {
        val emptyRule = TtsRule(pattern = "   ")
        assertTrue(emptyRule.validate() is RuleValidationResult.Invalid)

        val invalidRegex = TtsRule(pattern = "[a-z", isRegex = true)
        assertTrue(invalidRegex.validate() is RuleValidationResult.Invalid)

        val validRegex = TtsRule(pattern = "\\[.*?\\]", isRegex = true)
        assertTrue(validRegex.validate() is RuleValidationResult.Valid)

        val validPlain = TtsRule(pattern = "Tomato", isRegex = false)
        assertTrue(validPlain.validate() is RuleValidationResult.Valid)

        val invalidSkipRegexType = TtsRule(pattern = "[a-z", ruleType = TtsRuleType.SKIP_REGEX)
        assertTrue(invalidSkipRegexType.validate() is RuleValidationResult.Invalid)

        val validSkipRegexType = TtsRule(pattern = "\\d+", ruleType = TtsRuleType.SKIP_REGEX)
        assertTrue(validSkipRegexType.validate() is RuleValidationResult.Valid)
    }

    @Test
    fun `skip regex ruleType removes matching patterns`() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP_REGEX,
            pattern = "\\s*\\(Note:.*?\\)",
            caseSensitive = false
        )
        processor.setRules(listOf(rule))

        val input = "He began to cultivate. (Note: cultivation means gathering Qi) The room was silent."
        val expected = "He began to cultivate. The room was silent."
        assertEquals(expected, processor.process(input))
    }

    @Test
    fun `entity mapping round-trips correctly for all rule types`() {
        val rules = listOf(
            TtsRule(id = "1", ruleType = TtsRuleType.SKIP, pattern = "Tomato", wholeWord = true, sortOrder = 0),
            TtsRule(id = "2", ruleType = TtsRuleType.SKIP_REGEX, pattern = "\\[.*?\\]", sortOrder = 1),
            TtsRule(id = "3", ruleType = TtsRuleType.REPLACE, pattern = "Qi", replacement = "Chee", sortOrder = 2)
        )

        val entities = rules.map { it.toEntity() }
        val restored = entities.map { it.toModel() }

        assertEquals(rules.size, restored.size)
        assertEquals(TtsRuleType.SKIP, restored[0].ruleType)
        assertEquals(TtsRuleType.SKIP_REGEX, restored[1].ruleType)
        assertTrue(restored[1].isRegex)
        assertEquals(TtsRuleType.REPLACE, restored[2].ruleType)
        assertEquals("Chee", restored[2].replacement)
    }

    @Test
    fun `spacing cleanup removes double spaces and spaces before punctuation`() {
        val input = "Hello  , world  ! How  are   you ?"
        val expected = "Hello, world! How are you?"
        assertEquals(expected, TtsTextProcessor.cleanSpacing(input))
    }
}

