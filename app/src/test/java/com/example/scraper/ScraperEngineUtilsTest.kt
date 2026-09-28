package com.example.scraper

import com.example.scraper.engine.ChapterText
import com.example.scraper.engine.ChapterValidator
import com.example.scraper.engine.DomScripts
import com.example.scraper.engine.NavigationResolver
import com.example.scraper.engine.PageAnalyzer
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.NextChapterCandidate
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.WebsiteStrategy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScraperEngineUtilsTest {

    @Test
    fun normalizeKeepsChapterQueryParamsAndDropsTracking() {
        assertEquals("https://site.com/read.php?id=12", UrlIdentity.normalize("HTTPS://Site.COM/read.php?id=12&utm_source=x#top"))
        assertEquals(
            UrlIdentity.normalize("https://site.com/read.php?b=2&a=1"),
            UrlIdentity.normalize("https://site.com/read.php?a=1&b=2")
        )
        assertFalse(UrlIdentity.normalize("https://site.com/read.php?id=12") == UrlIdentity.normalize("https://site.com/read.php?id=13"))
        assertEquals("https://site.com/novel/ch-1", UrlIdentity.normalize("https://site.com:443/novel/ch-1/"))
        assertEquals("https://site.com", UrlIdentity.normalize("https://site.com/"))
        assertEquals("", UrlIdentity.normalize(null))
    }

    @Test
    fun siteHostIgnoresWwwAndMobilePrefixes() {
        assertEquals("tomatomtl.com", UrlIdentity.siteHost("https://www.tomatomtl.com/book/1"))
        assertEquals("site.com", UrlIdentity.siteHost("https://m.site.com/x"))
        assertTrue(UrlIdentity.isSameSite("https://www.site.com/a", "https://m.site.com/b"))
        assertFalse(UrlIdentity.isSameSite("https://site.com/a", "https://evil.com/b"))
    }

    @Test
    fun chapterNumbersFromTitlesAndUrls() {
        assertEquals(12.0, UrlIdentity.chapterNumber("Chapter 12: The Return")!!, 0.0)
        assertEquals(3.5, UrlIdentity.chapterNumber("Ch. 3.5 - Interlude")!!, 0.0)
        assertEquals(128.0, UrlIdentity.chapterNumber("第128章 回归")!!, 0.0)
        assertEquals(123.0, UrlIdentity.chapterNumber("第一百二十三章 回归")!!, 0.0)
        assertEquals(10.0, UrlIdentity.chapterNumber("第十章")!!, 0.0)
        assertEquals(45.0, UrlIdentity.chapterNumber("", "https://site.com/novel/chapter-45/")!!, 0.0)
        assertNull("long numeric ids are not chapter numbers", UrlIdentity.chapterNumber("", "https://site.com/book/7123456789012345678/7234567890123456789"))
        assertNull(UrlIdentity.chapterNumber("A quiet night", null))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(1, UrlIdentity.parseChineseNumber("一"))
        assertEquals(15, UrlIdentity.parseChineseNumber("十五"))
        assertEquals(20, UrlIdentity.parseChineseNumber("二十"))
        assertEquals(1004, UrlIdentity.parseChineseNumber("一千零四"))
        assertEquals(12000, UrlIdentity.parseChineseNumber("一万二千"))
    }

    @Test
    fun cjkRatioSeparatesTranslatedFromUntranslatedText() {
        assertTrue(UrlIdentity.cjkRatio("这是一个测试句子") > 0.9)
        assertEquals(0.0, UrlIdentity.cjkRatio("A fully English sentence."), 0.0)
        assertTrue(UrlIdentity.cjkRatio("Lin Feng (林枫) smiled and walked away from the crowd quietly.") < 0.2)
    }

    @Test
    fun contentHashIgnoresWhitespaceDifferences() {
        assertEquals(UrlIdentity.contentHash(listOf("a  b", "c")), UrlIdentity.contentHash(listOf("a b ", " c")))
        assertFalse(UrlIdentity.contentHash(listOf("a")) == UrlIdentity.contentHash(listOf("b")))
    }

    @Test
    fun nextChapterPrefersTheNextNumberAndRejectsVisitedOrForeignLinks() {
        val current = "https://site.com/novel/chapter-5"
        val candidates = listOf(
            NextChapterCandidate("https://site.com/novel/chapter-4", "Previous Chapter", 80.0, "text"),
            NextChapterCandidate("https://discord.gg/abc", "Next", 80.0, "text"),
            NextChapterCandidate("https://site.com/novel", "Table of Contents", 60.0, "class"),
            NextChapterCandidate("https://site.com/novel/chapter-9", "Next", 70.0, "label"),
            NextChapterCandidate("https://site.com/novel/chapter-6", "Next", 70.0, "label")
        )
        val chosen = NavigationResolver.selectNext(candidates, current, 5.0, emptySet())
        assertEquals("https://site.com/novel/chapter-6", chosen?.url)

        val visited = setOf(UrlIdentity.normalize("https://site.com/novel/chapter-6"))
        assertEquals("https://site.com/novel/chapter-9", NavigationResolver.selectNext(candidates, current, 5.0, visited)?.url)
    }

    @Test
    fun synthesizesOnlyUrlsWithAnExplicitChapterMarker() {
        assertEquals("https://site.com/n/chapter-13", NavigationResolver.synthesizeNext("https://site.com/n/chapter-12", 12.0))
        assertEquals("https://site.com/n/c010.html", NavigationResolver.synthesizeNext("https://site.com/n/c009.html", 9.0))
        assertEquals("https://site.com/n/ch-3?x=1", NavigationResolver.synthesizeNext("https://site.com/n/ch-2?x=1", 2.0))
        assertNull(NavigationResolver.synthesizeNext("https://site.com/book/12", 12.0))
        assertNull(NavigationResolver.synthesizeNext("https://site.com/n/abc12", 12.0))
        assertNull(NavigationResolver.synthesizeNext("https://site.com/n/chapter-12", null))
    }

    @Test
    fun cleanParagraphsRemovesBoilerplateButKeepsSceneBreaks() {
        val cleaned = ChapterText.cleanParagraphs(
            listOf("Chapter 3: Storm", "  First line.  ", "", "Next Chapter", "« Prev | Next »", "* * *", "Join our Discord for updates!", "Last line."),
            chapterTitle = "Chapter 3: Storm"
        )
        assertEquals(listOf("First line.", "* * *", "Last line."), cleaned)
    }

    @Test
    fun xhtmlParagraphsAreEscapedAndRoundTrip() {
        val paragraphs = listOf("Tom & Jerry <3", "\"Quoted\" and 'single'", "bad\u0001char and \uD800 lone surrogate")
        val xhtml = ChapterText.toXhtmlParagraphs(paragraphs)
        assertFalse(xhtml.contains("<3"))
        assertFalse(xhtml.contains('\u0001'))
        assertEquals(listOf("Tom & Jerry <3", "\"Quoted\" and 'single'", "badchar and  lone surrogate"), ChapterText.fromStored(xhtml))
    }

    @Test
    fun pageAnalyzerDecodesTheEvaluateJavascriptEnvelope() {
        val payload = JSONObject()
            .put("url", "https://site.com/c/1")
            .put("chapterTitle", "  Chapter 1 | Site  ")
            .put("novelTitle", "Great Novel")
            .put("paragraphs", JSONArray(listOf("Chapter 1 | Site", "Line with \"quotes\" and \\ backslash\nand newline", "Second")))
            .put("linkDensity", 0.05)
            .put("elementFound", true)
            .put("nextCandidates", JSONArray().put(JSONObject().put("url", "https://site.com/c/2").put("text", "Next").put("score", 80)))
        val raw = JSONObject.quote(payload.toString())

        val result = PageAnalyzer.parse(raw, "https://fallback")
        assertNotNull(result)
        result!!
        assertEquals("Chapter 1 | Site", result.chapterTitle)
        assertEquals("Great Novel", result.novelTitle)
        assertEquals(2, result.paragraphCount)
        assertTrue(result.paragraphs[0].contains("\\ backslash"))
        assertEquals("https://site.com/c/2", result.nextCandidates.single().url)

        assertNull(PageAnalyzer.parse(null, "x"))
        assertNull(PageAnalyzer.parse("null", "x"))
        assertNull(PageAnalyzer.parse(JSONObject.quote("{\"error\":\"boom\"}"), "x"))
        assertNull(PageAnalyzer.parse("\"not json\"", "x"))
    }

    @Test
    fun validatorRejectsMenusShortTextAndBlockedPages() {
        val settings = ScrapeSettings(minCharacterCount = 100)
        val good = sample(listOf("A".repeat(300), "B".repeat(300)))
        assertTrue(ChapterValidator.validate(good, settings).isValid)
        assertFalse(ChapterValidator.validate(good.copy(linkDensity = 0.6), settings).isValid)
        assertFalse(ChapterValidator.validate(sample(listOf("short")), settings).isValid)
        assertFalse(ChapterValidator.validate(good.copy(isErrorPage = true), settings).isValid)
        assertFalse(ChapterValidator.validate(good.copy(isLoginWall = true), settings).isValid)
        assertTrue("one long paragraph is fine", ChapterValidator.validate(sample(listOf("C".repeat(900))), settings).isValid)
    }

    @Test
    fun scriptsEmbedSelectorsSafely() {
        val js = DomScripts.extract(WebsiteStrategy("site.com", "div[data-x=\"a\"] > p", "h1.title", "a#next"))
        assertTrue(js.startsWith(DomScripts.MARKER_EXTRACT))
        assertTrue(js.contains("content:\"div[data-x=\\\"a\\\"] > p\""))
        assertTrue(DomScripts.discover().startsWith(DomScripts.MARKER_DISCOVER))
        assertFalse("no Kotlin template leaked into the script", DomScripts.discover().contains("\${"))
    }

    private fun sample(paragraphs: List<String>) = com.example.scraper.engine.ExtractionResult(
        url = "https://site.com/c/1",
        canonicalUrl = "https://site.com/c/1",
        novelTitle = "",
        chapterTitle = "Chapter 1",
        paragraphs = paragraphs,
        characterCount = paragraphs.sumOf { it.length },
        linkDensity = 0.01,
        elementFound = true,
        isLoginWall = false,
        isChallenge = false,
        isErrorPage = false,
        contentSelector = "#c",
        titleSelector = "h1",
        nextSelector = "",
        nextCandidates = emptyList(),
        atBottom = true
    )
}
