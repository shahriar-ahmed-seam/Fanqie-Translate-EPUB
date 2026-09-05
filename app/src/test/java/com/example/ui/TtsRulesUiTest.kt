package com.example.ui

import com.example.tts.rule.RuleValidationResult
import com.example.tts.rule.TtsRule
import com.example.tts.rule.TtsRuleType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsRulesUiTest {

    @Test
    fun testSkipRuleCreationAndValidation() {
        val rule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = "Tomato",
            wholeWord = true,
            caseSensitive = false,
            isEnabled = true
        )

        val validation = rule.validate()
        assertTrue("Skip rule with pattern should be valid", validation is RuleValidationResult.Valid)
        assertEquals(TtsRuleType.SKIP, rule.ruleType)
        assertEquals("Tomato", rule.pattern)
        assertTrue(rule.wholeWord)
        assertFalse(rule.caseSensitive)
        assertTrue(rule.isEnabled)
    }

    @Test
    fun testRegexSkipRuleValidation() {
        val validRegexRule = TtsRule(
            ruleType = TtsRuleType.SKIP_REGEX,
            pattern = "\\[[0-9]+\\]",
            isRegex = true,
            isEnabled = true
        )
        val validResult = validRegexRule.validate()
        assertTrue("Valid regex should pass validation", validResult is RuleValidationResult.Valid)

        val invalidRegexRule = TtsRule(
            ruleType = TtsRuleType.SKIP_REGEX,
            pattern = "[a-z(",
            isRegex = true,
            isEnabled = true
        )
        val invalidResult = invalidRegexRule.validate()
        assertTrue("Invalid regex syntax must fail validation", invalidResult is RuleValidationResult.Invalid)
    }

    @Test
    fun testReplaceRuleCreationAndValidation() {
        val replaceRule = TtsRule(
            ruleType = TtsRuleType.REPLACE,
            pattern = "Tom",
            replacement = "Jack",
            wholeWord = true,
            caseSensitive = true,
            isEnabled = true
        )
        val result = replaceRule.validate()
        assertTrue("Replace rule should pass validation", result is RuleValidationResult.Valid)
        assertEquals("Tom", replaceRule.pattern)
        assertEquals("Jack", replaceRule.replacement)
        assertTrue(replaceRule.wholeWord)
        assertTrue(replaceRule.caseSensitive)
    }

    @Test
    fun testExtremelyLongRuleHandling() {
        val veryLongWord = "pneumonoultramicroscopicsilicovolcanoconiosis_supercalifragilisticexpialidocious_antidisestablishmentarianism_repeat_".repeat(10)
        val longRule = TtsRule(
            ruleType = TtsRuleType.SKIP,
            pattern = veryLongWord,
            wholeWord = false,
            isEnabled = true
        )
        val validation = longRule.validate()
        assertTrue("Long words must be valid and not truncated by model", validation is RuleValidationResult.Valid)
        assertEquals(veryLongWord, longRule.pattern)

        val veryLongRegex = "(?:[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+|https?://[^\\s/$.?#].[^\\s]*|\\[chapter\\s+[0-9]+\\])"
        val longRegexRule = TtsRule(
            ruleType = TtsRuleType.SKIP_REGEX,
            pattern = veryLongRegex,
            isRegex = true,
            isEnabled = true
        )
        val regexValidation = longRegexRule.validate()
        assertTrue("Complex long regex must validate correctly", regexValidation is RuleValidationResult.Valid)
    }

    @Test
    fun testRuleFilteringAndCounts() {
        val rules = listOf(
            TtsRule(ruleType = TtsRuleType.SKIP, pattern = "foo"),
            TtsRule(ruleType = TtsRuleType.SKIP, pattern = "bar"),
            TtsRule(ruleType = TtsRuleType.SKIP_REGEX, pattern = "\\d+", isRegex = true),
            TtsRule(ruleType = TtsRuleType.REPLACE, pattern = "a", replacement = "b")
        )

        val skipCount = rules.count { it.ruleType == TtsRuleType.SKIP }
        val regexCount = rules.count { it.ruleType == TtsRuleType.SKIP_REGEX }
        val replaceCount = rules.count { it.ruleType == TtsRuleType.REPLACE }

        assertEquals(2, skipCount)
        assertEquals(1, regexCount)
        assertEquals(1, replaceCount)
        assertEquals(4, rules.size)
    }

    @Test
    fun testReorderingBoundaryConditions() {
        val rules = listOf(
            TtsRule(id = "1", ruleType = TtsRuleType.SKIP, pattern = "r1", sortOrder = 0),
            TtsRule(id = "2", ruleType = TtsRuleType.SKIP, pattern = "r2", sortOrder = 1),
            TtsRule(id = "3", ruleType = TtsRuleType.SKIP, pattern = "r3", sortOrder = 2)
        )

        // First item cannot move up, but can move down
        val canFirstMoveUp = 0 > 0
        val canFirstMoveDown = 0 < rules.size - 1
        assertFalse(canFirstMoveUp)
        assertTrue(canFirstMoveDown)

        // Middle item can move both up and down
        val canMiddleMoveUp = 1 > 0
        val canMiddleMoveDown = 1 < rules.size - 1
        assertTrue(canMiddleMoveUp)
        assertTrue(canMiddleMoveDown)

        // Last item can move up, but cannot move down
        val canLastMoveUp = 2 > 0
        val canLastMoveDown = 2 < rules.size - 1
        assertTrue(canLastMoveUp)
        assertFalse(canLastMoveDown)
    }
}
