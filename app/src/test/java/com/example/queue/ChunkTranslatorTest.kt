package com.example.queue

import com.example.translation.TranslationException
import com.example.translation.TranslationProvider
import com.example.translation.TranslationQualityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own (queue, TTS) isn't needed and would keep running after the test.
@Config(sdk = [34], application = android.app.Application::class)
class ChunkTranslatorTest {

    private val zh = listOf("他推开门走了进去，屋里一片漆黑。", "桌子上放着一封信，信封已经发黄了。", "他拿起信，手微微发抖。")
    private val en = listOf(
        "He pushed the door open and went in; the room was pitch dark.",
        "A letter lay on the table; the envelope had yellowed.",
        "He picked up the letter, his hand trembling slightly."
    )
    private val sourceChunk = zh.joinToString("\n\n")

    private class FakeProvider(private val answer: (String) -> Result<String>) : TranslationProvider {
        val requests = mutableListOf<String>()
        override val name = "fake"
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> {
            requests += text
            return answer(text)
        }
    }

    /** Translates single paragraphs correctly; whole chunks get [chunkAnswer]. */
    private fun provider(chunkAnswer: String) = FakeProvider { text ->
        val index = zh.indexOf(text)
        Result.success(if (index >= 0) en[index] else chunkAnswer)
    }

    private fun translate(provider: TranslationProvider, type: String = "CHAPTER_BODY", text: String = sourceChunk) =
        runBlocking { ChunkTranslator { provider }.translate(type, text) }

    @Test
    fun aParagraphLeftInChineseIsTranslatedAgainOnItsOwn() {
        val fake = provider(listOf(en[0], zh[1], en[2]).joinToString("\n\n"))
        val result = translate(fake)
        assertEquals(en.joinToString("\n\n"), result.getOrNull())
        assertEquals(listOf(sourceChunk, zh[1]), fake.requests)
    }

    @Test
    fun anEmptyAnswerIsAFailureNeverATranslation() {
        val fake = provider("[\"\"]")
        val error = translate(fake).exceptionOrNull()
        assertTrue(error is TranslationQualityException && error.isSoftFailure)
        assertEquals("no paragraph-by-paragraph requests for an empty answer", 1, fake.requests.size)
    }

    @Test
    fun aMostlyChineseAnswerIsRetriedLaterInsteadOfParagraphByParagraph() {
        val four = (zh + "她在门外等了很久很久。").joinToString("\n\n")
        val fake = FakeProvider { Result.success(it) } // Google sent the Chinese back
        val error = translate(fake, text = four).exceptionOrNull() as TranslationQualityException
        assertEquals(4, error.badParagraphs)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun mergedParagraphsWithChineseLeftAreFixed() {
        val fake = provider("${en[0]} ${en[1]}\n\n${zh[2]}")
        val result = translate(fake)
        // The Chinese paragraph is translated on its own; Google's merge of the first two is kept.
        assertEquals("${en[0]} ${en[1]}\n\n${en[2]}", result.getOrNull())
        assertEquals(listOf(sourceChunk, zh[2]), fake.requests)
    }

    @Test
    fun chineseWordsLeftInsideEnglishSentencesAreTranslated() {
        // Seen live: Google kept 三生石 and 跳？ untranslated inside otherwise English paragraphs.
        val fake = FakeProvider { text ->
            when (text) {
                sourceChunk -> Result.success(listOf("跳？ Dance? !", "A tablet reads \"三生石\" in old script.", en[2]).joinToString("\n\n"))
                "跳？\n\n三生石" -> Result.success("Jump?\n\nThree Life Stones")
                else -> Result.failure(AssertionError("unexpected request: $text"))
            }
        }
        val result = translate(fake)
        assertEquals(listOf("Jump? Dance? !", "A tablet reads \"Three Life Stones\" in old script.", en[2]).joinToString("\n\n"), result.getOrNull())
        assertEquals("the words are translated in one request", 2, fake.requests.size)
    }

    @Test
    fun aTitleStillInChineseIsAFailure() {
        val fake = FakeProvider { Result.success("Chapter 1 空屋") }
        val error = translate(fake, type = "CHAPTER_TITLE", text = "第1章 空屋").exceptionOrNull()
        assertTrue(error is TranslationQualityException)
        assertEquals("Chapter 1 空屋", (error as TranslationQualityException).bestText)

        val good = FakeProvider { Result.success("Chapter 1 Empty House") }
        assertEquals("Chapter 1 Empty House", translate(good, type = "CHAPTER_TITLE", text = "第1章 空屋").getOrNull())

        // The Chinese part of a half-translated title is translated on its own.
        val half = FakeProvider { text -> Result.success(if (text == "空屋") "Empty House" else "Chapter 1 空屋") }
        assertEquals("Chapter 1 Empty House", translate(half, type = "CHAPTER_TITLE", text = "第1章 空屋").getOrNull())
    }

    @Test
    fun aSavedTranslationIsOnlyFixedWhereChineseIsLeft() = runBlocking {
        val translator = { p: FakeProvider -> ChunkTranslator { p } }
        // Clean: kept as it is, no request.
        val none = FakeProvider { Result.failure(AssertionError("no request expected")) }
        assertEquals(en.joinToString("\n\n"), translator(none).retranslate("CHAPTER_BODY", sourceChunk, en.joinToString("\n\n")).getOrNull())

        // One paragraph left in Chinese: only that paragraph is sent.
        val one = provider("unused")
        val stored = listOf(en[0], zh[1], en[2]).joinToString("\n\n")
        assertEquals(en.joinToString("\n\n"), translator(one).retranslate("CHAPTER_BODY", sourceChunk, stored).getOrNull())
        assertEquals(listOf(zh[1]), one.requests)

        // Junk: translated again from scratch.
        val fresh = provider(en.joinToString("\n\n"))
        assertEquals(en.joinToString("\n\n"), translator(fresh).retranslate("CHAPTER_BODY", sourceChunk, "[\"\"]").getOrNull())
        assertEquals(listOf(sourceChunk), fresh.requests)
    }

    @Test
    fun laterAttemptsTranslateAStubbornChunkParagraphByParagraph() = runBlocking {
        // Google answers the whole chunk empty every time, but single paragraphs fine.
        val fake = provider("[\"\"]")
        val normal = ChunkTranslator { fake }.translate("CHAPTER_BODY", sourceChunk)
        assertTrue(normal.exceptionOrNull() is TranslationQualityException)
        assertEquals(1, fake.requests.size)

        val thorough = ChunkTranslator { fake }.translate("CHAPTER_BODY", sourceChunk, thorough = true)
        assertEquals(en.joinToString("\n\n"), thorough.getOrNull())
        assertEquals(1 + 1 + zh.size, fake.requests.size)
    }

    @Test
    fun anAttemptMakesALimitedNumberOfRequests() = runBlocking {
        // 50 paragraphs, Google merges them and leaves Chinese: a normal attempt doesn't translate
        // them one by one (that would be 50 more requests).
        val many = (1..50).map { "第${it}段文字很长很长。" }
        val fake = FakeProvider { text -> Result.success(if (text.contains("\n\n")) "Merged text. 第一段文字很长很长。" else "Paragraph.") }
        ChunkTranslator { fake }.translate("CHAPTER_BODY", many.joinToString("\n\n"))
        assertTrue("requests: ${fake.requests.size}", fake.requests.size <= 32)
    }

    @Test
    fun textWithoutChineseIsNotSent() {
        val fake = FakeProvider { Result.success("should not be asked") }
        assertEquals("* * *", translate(fake, text = "* * *").getOrNull())
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun rateLimitingDuringARepairStopsTheRepair() {
        val limited = TranslationException("429", statusCode = 429, isRateLimited = true)
        val fake = FakeProvider { text ->
            if (text == zh[1]) Result.failure(limited) else Result.success(listOf(en[0], zh[1], en[2]).joinToString("\n\n"))
        }
        assertEquals(limited, translate(fake).exceptionOrNull())
    }

    @Test
    fun aFewStubbornParagraphsCanBeKeptAsALastResort() {
        val stubborn = FakeProvider { text ->
            // Paragraph 1 never translates; the rest do.
            if (text == zh[1]) Result.success(zh[1]) else Result.success(listOf(en[0], zh[1], en[2]).joinToString("\n\n"))
        }
        val error = translate(stubborn).exceptionOrNull() as TranslationQualityException
        assertEquals(1, error.badParagraphs)
        assertEquals(listOf(en[0], zh[1], en[2]).joinToString("\n\n"), error.bestText)
        assertTrue(ChunkTranslator.isAcceptableLastResort(error))

        assertFalse(ChunkTranslator.isAcceptableLastResort(TranslationQualityException("x", bestText = "a\n\nb", badParagraphs = 3, totalParagraphs = 12)))
        assertFalse(ChunkTranslator.isAcceptableLastResort(TranslationQualityException("x", bestText = null, badParagraphs = 1, totalParagraphs = 12)))
        assertFalse(ChunkTranslator.isAcceptableLastResort(TranslationQualityException("x", bestText = "[\"\"]", badParagraphs = 1, totalParagraphs = 12)))
    }
}
