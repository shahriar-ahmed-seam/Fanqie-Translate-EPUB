package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric provides the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own (queue, TTS) isn't needed and would keep running after the test.
@Config(sdk = [34], application = android.app.Application::class)
class TomatoMTLProviderParseTest {

    @Test
    fun emptyAnswersParseToNothingInsteadOfTheRawBody() {
        // Google's answer for an empty translation. It used to be stored as the chapter text.
        assertEquals("", TomatoMTLProvider.parseTEndpoint("[\"\"]"))
        assertEquals("", TomatoMTLProvider.parseTEndpoint("[]"))
        assertEquals("", TomatoMTLProvider.parseSingleEndpoint("[null,null,\"zh-CN\"]"))
        assertEquals("", TomatoMTLProvider.parseSingleEndpoint("[[[null,null,\"Dì yī zhāng\"]],null,\"zh-CN\"]"))
    }

    @Test
    fun tEndpointAnswersAreRead() {
        assertEquals("Chapter 1 Empty House", TomatoMTLProvider.parseTEndpoint("[\"Chapter 1 Empty House\"]"))
        assertEquals("Hello\n\nWorld", TomatoMTLProvider.parseTEndpoint("[\"Hello\\n\\nWorld\"]"))
        // With language detection the text comes with the detected language: only the text is kept.
        assertEquals("Hello", TomatoMTLProvider.parseTEndpoint("[[\"Hello\",\"zh-CN\"]]"))
    }

    @Test
    fun singleEndpointKeepsOnlyTheTranslatedSegments() {
        val oneSegment = "[[[\"Chapter 1 Empty House\",\"第1章 空屋\",null,null,10]],null,\"zh-CN\",null,null,null,null,[]]"
        assertEquals("Chapter 1 Empty House", TomatoMTLProvider.parseSingleEndpoint(oneSegment))

        val twoSegments = "[[[\"He left. \",\"他走了。\",null,null,10],[\"She stayed.\",\"她留下了。\",null,null,10]]," +
            "null,\"zh-CN\",null,null,null,1,[],[[\"zh-CN\"],null,[0.97],[\"zh-CN\"]]]"
        assertEquals("He left. She stayed.", TomatoMTLProvider.parseSingleEndpoint(twoSegments))
    }
}
