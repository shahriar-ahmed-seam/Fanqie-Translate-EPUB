package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityTest {

    @Test
    fun rawEmptyAnswersAreNeverUsable() {
        for (junk in listOf("[\"\"]", " [\"\"] ", "[\"\u3000\u3000\"]", "[\"\\u3000\\u3000\"]", "[]", "[null,null,\"zh-CN\"]", "", "   ", "zh-CN")) {
            assertNull("'$junk' must not be shown as a translation", TranslationQuality.usable(junk))
        }
        // Real sentences in brackets and quotes are kept.
        assertEquals("[\"Congratulations to the host\"]", TranslationQuality.usable("[\"Congratulations to the host\"]"))
        assertEquals("[Ding! System activated]", TranslationQuality.usable("[Ding! System activated]"))
    }

    @Test
    fun languageCodesGluedByTheOldFallbackParserAreRemoved() {
        assertEquals("Chapter 1 Empty House", TranslationQuality.usable("Chapter 1 Empty Housezh-CN", "第1章 空屋"))
        assertEquals("He left.", TranslationQuality.usable("He left.zh-CNzh-CN0.97zh-CN"))
        assertEquals("Chapter 1 Empty House", TranslationQuality.usableTitle("Chapter 1 Empty Housezh-CN"))
    }

    @Test
    fun chineseLeftInTheOutputIsDetected() {
        val source = "他看着窗外的雨，心里想着明天该怎么办。"
        assertTrue("echo", TranslationQuality.isBad(source, source))
        assertTrue("mostly Chinese", TranslationQuality.isBad(source, "He 看着窗外的雨，心里想着明天"))
        assertTrue("an untranslated sentence inside English", TranslationQuality.isBad(source + source, "He looked at the rain outside. 心里想着明天该怎么办 he wondered."))
        assertFalse("a clean translation", TranslationQuality.isBad(source, "He looked at the rain outside the window, wondering what to do tomorrow."))
        assertFalse("a name Google kept is fine", TranslationQuality.isBad(source, "Wang 小 looked at the rain outside the window and thought about tomorrow."))
        assertFalse("no Chinese in the source: anything goes", TranslationQuality.isBad("……", ""))
        assertTrue("empty answer for Chinese", TranslationQuality.isBad(source, "[\"\"]"))
    }

    @Test
    fun titlesMustBeFullyTranslated() {
        assertTrue(TranslationQuality.isUntranslated("第1章 空屋", "第1章 空屋", isTitle = true))
        assertTrue(TranslationQuality.isUntranslated("第1章 空屋", "Chapter 1 空屋", isTitle = true))
        assertFalse(TranslationQuality.isUntranslated("第1章 空屋", "Chapter 1 Empty House", isTitle = true))
        assertNull(TranslationQuality.usableTitle("第十二章 风起"))
        assertNull("the Chinese sent back", TranslationQuality.usableTitle("第1章 空屋", "第1章 空屋"))
        assertEquals("Chapter 12 The Wind Rises", TranslationQuality.usableTitle("Chapter 12 The Wind Rises"))
        // Shown as before (v1.2.0); new translations of such titles are fixed by the translator.
        assertEquals("Chapter 1 空屋", TranslationQuality.usableTitle("Chapter 1 空屋", "第1章 空屋"))
    }

    @Test
    fun wholeAnswersThatCameBackChineseAskForTheFallback() {
        val source = "第一段。\n\n第二段，很长的一段话在这里。"
        assertFalse(TranslationQuality.looksTranslated(source, "[\"\"]"))
        assertFalse(TranslationQuality.looksTranslated(source, source))
        assertFalse("short texts like titles must have no Chinese left", TranslationQuality.looksTranslated("第1章 空屋", "Chapter 1 空屋"))
        assertTrue(TranslationQuality.looksTranslated(source, "First paragraph.\n\nThe second paragraph, a long passage here."))
        assertTrue(TranslationQuality.looksTranslated("***", ""))
    }

    @Test
    fun chunksAreCheckedParagraphByParagraph() {
        val source = listOf("甲说了一句很长的话。", "乙回答了他的问题。", "丙站在门口看着他们两个人。").joinToString("\n\n")
        val clean = TranslationQuality.check(source, "A said a long sentence.\n\nB answered his question.\n\nC stood at the door watching the two of them.")
        assertTrue(clean.isClean)

        val oneBad = TranslationQuality.check(source, "A said a long sentence.\n\n乙回答了他的问题。\n\nC stood at the door watching the two of them.")
        assertEquals(listOf(1), oneBad.badIndices)
        assertFalse(oneBad.countMismatch)

        val merged = TranslationQuality.check(source, "A said a long sentence. B answered.\n\n丙站在门口看着他们两个人。")
        assertTrue(merged.countMismatch)
        assertEquals(1, merged.badUnpaired)

        val empty = TranslationQuality.check(source, "[\"\"]")
        assertTrue(empty.countMismatch)
        assertEquals(3, empty.badUnpaired)
    }
}
