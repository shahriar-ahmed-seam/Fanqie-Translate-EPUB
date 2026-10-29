package com.example.epub

import com.example.data.db.TranslationChunkEntity
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubRebuilderTest {

    @Test
    fun testRebuildChapterXhtmlPreservesImagesAndPreventsTextLeak() {
        val originalXhtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>第一章</title></head>
            <body>
                <h1>第一章：初入江湖</h1>
                <p>这是第一段中文正文内容。</p>
                <p><img src="images/illustration1.jpg" alt="Illustration 1" /></p>
                <p>这是第二段中文正文内容。</p>
                <p>这是第三段中文正文内容，将在翻译时被截断或合并。</p>
                <p>这是第四段多余的中文正文内容，绝不能泄露到最终的英文EPUB中。</p>
            </body>
            </html>
        """.trimIndent()

        // Suppose translation yielded only 2 body paragraphs
        val chunks = listOf(
            TranslationChunkEntity(
                id = "chunk-1",
                jobId = "job-1",
                bookId = "book-1",
                chapterId = "ch-1",
                chapterOrder = 0,
                chunkOrder = 0,
                chunkType = "CHAPTER_BODY",
                sourceText = "这是第一段中文正文内容。\n\n这是第二段中文正文内容。",
                translatedText = "This is the first translated English paragraph.\n\nThis is the second translated English paragraph.",
                status = "COMPLETED"
            )
        )

        val rebuilt = EpubRebuilder.rebuildChapterXhtml(
            rawXhtml = originalXhtml,
            translatedTitle = "Chapter 1: Entering the Martial World",
            chapterChunks = chunks
        )

        val doc = Jsoup.parse(rebuilt)

        // 1. Check title & heading
        assertEquals("Chapter 1: Entering the Martial World", doc.title())
        assertEquals("Chapter 1: Entering the Martial World", doc.select("h1").text())

        // 2. Check that the image element was NOT destroyed
        val img = doc.select("img").first()
        assertNotNull("Illustration image must be preserved", img)
        assertEquals("images/illustration1.jpg", img?.attr("src"))

        // 3. Check translated text
        assertTrue("Must contain translated paragraph 1", doc.text().contains("This is the first translated English paragraph."))
        assertTrue("Must contain translated paragraph 2", doc.text().contains("This is the second translated English paragraph."))

        // 4. Check that untranslated Chinese text is NOT leaked
        assertFalse("Must not leak third Chinese paragraph", doc.text().contains("这是第三段中文正文内容"))
        assertFalse("Must not leak fourth Chinese paragraph", doc.text().contains("这是第四段多余的中文正文内容"))
    }

    @Test
    fun testRebuildChapterXhtmlExpandsParagraphsWhenTranslationHasMore() {
        val originalXhtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Chapter 2</title></head>
            <body>
                <h1>Chapter 2</h1>
                <p>Single source paragraph.</p>
            </body>
            </html>
        """.trimIndent()

        val chunks = listOf(
            TranslationChunkEntity(
                id = "chunk-2",
                jobId = "job-1",
                bookId = "book-1",
                chapterId = "ch-2",
                chapterOrder = 1,
                chunkOrder = 0,
                chunkType = "CHAPTER_BODY",
                sourceText = "Single source paragraph.",
                translatedText = "First translated paragraph.\n\nSecond translated paragraph.\n\nThird translated paragraph.",
                status = "COMPLETED"
            )
        )

        val rebuilt = EpubRebuilder.rebuildChapterXhtml(
            rawXhtml = originalXhtml,
            translatedTitle = "Chapter 2: Expanded",
            chapterChunks = chunks
        )

        val doc = Jsoup.parse(rebuilt)
        val pElements = doc.select("p")
        assertEquals(3, pElements.size)
        assertEquals("First translated paragraph.", pElements[0].text())
        assertEquals("Second translated paragraph.", pElements[1].text())
        assertEquals("Third translated paragraph.", pElements[2].text())
    }

    private fun chunk(order: Int, source: List<String>, translated: List<String>?) = TranslationChunkEntity(
        id = "c$order", jobId = "job", bookId = "book", chapterId = "ch", chapterOrder = 0, chunkOrder = order,
        chunkType = "CHAPTER_BODY", sourceText = source.joinToString("\n\n"),
        translatedText = translated?.joinToString("\n\n"), status = if (translated != null) "COMPLETED" else "PENDING"
    )

    /** Chunks exactly as the importer builds them from [xhtml] (current extraction). */
    private fun importedParagraphs(xhtml: String) = EpubParser.extractTranslatableParagraphs(Jsoup.parse(xhtml))

    private val novelChapter = """
        <html><head><title>第一章</title></head><body>
        <h1>第一章：初入江湖</h1>
        <p>甲段。</p>
        <blockquote><p>乙段信件。</p><p>丙段信件。</p></blockquote>
        <h2>小标题</h2>
        <p>丁段。</p>
        <p>戊段。</p>
        </body></html>
    """.trimIndent()

    @Test
    fun headingsAndNestedBlocksStayAlignedWithTheirTranslations() {
        val source = importedParagraphs(novelChapter)
        assertEquals("nested blockquote paragraphs are extracted once", listOf("第一章：初入江湖", "甲段。", "乙段信件。", "丙段信件。", "小标题", "丁段。", "戊段。"), source)
        val english = listOf("Chapter 1: Into the World", "Paragraph A.", "Letter B.", "Letter C.", "Subheading", "Paragraph D.", "Paragraph E.")
        val chunks = listOf(chunk(0, source.take(4), english.take(4)), chunk(1, source.drop(4), english.drop(4)))

        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "Chapter 1: Entering the Martial World", chunks))

        assertEquals("Chapter 1: Entering the Martial World", doc.select("h1").text())
        assertEquals("Subheading", doc.select("h2").text())
        assertEquals(listOf("Paragraph A.", "Letter B.", "Letter C.", "Paragraph D.", "Paragraph E."), doc.select("p").map { it.text() })
        assertEquals(listOf("Letter B.", "Letter C."), doc.select("blockquote p").map { it.text() })
    }

    @Test
    fun aChunkWhoseTranslationMergedParagraphsDoesNotShiftTheRestOfTheChapter() {
        val source = importedParagraphs(novelChapter)
        val chunks = listOf(
            // Google merged "A" and "B" into one paragraph in the first chunk.
            chunk(0, source.take(4), listOf("Chapter 1", "Paragraph A. Letter B.", "Letter C.")),
            chunk(1, source.drop(4), listOf("Subheading", "Paragraph D.", "Paragraph E."))
        )
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "Chapter 1", chunks))
        val paragraphs = doc.select("p").map { it.text() }
        assertEquals(listOf("Paragraph A. Letter B.", "Letter C.", "Paragraph D.", "Paragraph E."), paragraphs)
        assertFalse(doc.text().contains("段"))
    }

    @Test
    fun booksImportedByOlderVersionsExportCorrectly() {
        // v1.0.x extracted nested blocks twice: the blockquote itself and each paragraph in it.
        val legacySource = listOf("第一章：初入江湖", "甲段。", "乙段信件。 丙段信件。", "乙段信件。", "丙段信件。", "小标题", "丁段。", "戊段。")
        assertEquals(legacySource.size, EpubParser.legacyBlockElements(Jsoup.parse(novelChapter)).size)
        val english = listOf("Chapter 1", "Paragraph A.", "Letters B and C.", "Letter B.", "Letter C.", "Subheading", "Paragraph D.", "Paragraph E.")
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "Chapter 1", listOf(chunk(0, legacySource, english))))

        assertEquals("Subheading", doc.select("h2").text())
        assertEquals("Paragraph D.", doc.select("p").let { it[it.size - 2] }.text())
        assertEquals("Paragraph E.", doc.select("p").last()!!.text())
        assertFalse("no source text leaks", doc.text().contains("段"))
    }

    @Test
    fun untranslatedChunksFallBackToTheSourceTextInPlace() {
        val source = importedParagraphs(novelChapter)
        val chunks = listOf(chunk(0, source.take(4), listOf("Chapter 1", "Paragraph A.", "Letter B.", "Letter C.")), chunk(1, source.drop(4), null))
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "Chapter 1", chunks))
        assertEquals(listOf("Paragraph A.", "Letter B.", "Letter C.", "丁段。", "戊段。"), doc.select("p").map { it.text() })
    }

    @Test
    fun chaptersWithoutParagraphTagsAreSplitAtLineBreaksAndDoNotLeakSourceText() {
        val xhtml = "<html><body><div class=\"txt\">第一行。<br/>第二行。<br/><br/>第三行。<img src=\"a.png\"/></div></body></html>"
        val source = importedParagraphs(xhtml)
        assertEquals(listOf("第一行。", "第二行。", "第三行。"), source)
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(xhtml, "Chapter 9", listOf(chunk(0, source, listOf("Line one.", "Line two.", "Line three.")))))
        assertEquals(listOf("Line one.", "Line two.", "Line three."), doc.select("p").map { it.text() })
        assertFalse(doc.text().contains("行"))
        assertNotNull(doc.selectFirst("img[src=a.png]"))
    }

    @Test
    fun junkStoredByOlderVersionsIsNeverExported() {
        val source = importedParagraphs(novelChapter)
        val chunks = listOf(
            chunk(0, source.take(4), listOf("Chapter 1", "Paragraph A.", "Letter B.", "Letter C.")),
            chunk(1, source.drop(4), listOf("[\"\"]"))
        )
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "Chapter 1", chunks))
        assertFalse(doc.text().contains("[\"\"]"))
        assertEquals("the untranslated part keeps its source text", listOf("Paragraph A.", "Letter B.", "Letter C.", "丁段。", "戊段。"), doc.select("p").map { it.text() })
    }

    @Test
    fun anUntranslatedTitleNoLongerOverwritesTheTranslatedHeading() {
        val source = importedParagraphs(novelChapter)
        val english = listOf("Chapter 1: Into the World", "Paragraph A.", "Letter B.", "Letter C.", "Subheading", "Paragraph D.", "Paragraph E.")
        // No usable chapter-title translation: an empty title is passed.
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(novelChapter, "", listOf(chunk(0, source, english))))
        assertEquals("Chapter 1: Into the World", doc.select("h1").text())
        assertEquals("Chapter 1: Into the World", doc.title())
    }

    @Test
    fun aHeadingFollowedByLooseLinesIsTranslatedLineByLine() {
        val longLine = "这一行是很长的正文内容，用来模拟没有段落标签的章节。".repeat(4)
        val xhtml = "<html><head><title>第三章</title></head><body><h2>第三章 夜</h2><div>$longLine<br/>第二行文字也在这里。<br/>第三行。</div></body></html>"
        val source = importedParagraphs(xhtml)
        assertEquals("the story text outside block tags is imported", listOf("第三章 夜", longLine, "第二行文字也在这里。", "第三行。"), source)

        val english = listOf("Chapter 3 Night", "This is a long line of body text.", "The second line is here too.", "The third line.")
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(xhtml, "Chapter 3 Night", listOf(chunk(0, source, english))))
        // Each line is replaced where it is: the heading stays a heading, the line breaks stay.
        assertEquals(english, EpubParser.textLines(doc.body()))
        assertEquals("Chapter 3 Night", doc.select("h2").text())
        assertEquals(2, doc.select("div br").size)
        assertEquals(0, com.example.translation.TranslationQuality.hanCount(doc.body().text()))
        assertEquals("Chapter 3 Night", doc.title())
    }

    @Test
    fun linesStayInPlaceAroundPictures() {
        val longLine = "第一行很长的正文内容在这里，用来模拟没有段落标签的章节内容。".repeat(4)
        val xhtml = "<html><body><h1>第五章</h1><div>$longLine<br/><img src=\"x.png\"/><br/>第二行在图片后面。</div></body></html>"
        val source = importedParagraphs(xhtml)
        assertEquals(listOf("第五章", longLine, "第二行在图片后面。"), source)
        val english = listOf("Chapter 5", "The first long line.", "The second line, after the picture.")
        val doc = Jsoup.parse(EpubRebuilder.rebuildChapterXhtml(xhtml, "Chapter 5", listOf(chunk(0, source, english))))
        val div = doc.selectFirst("div")!!
        val html = div.html()
        assertTrue("the picture stays between the lines", html.indexOf("The first long line.") < html.indexOf("x.png") && html.indexOf("x.png") < html.indexOf("after the picture"))
        assertEquals(0, com.example.translation.TranslationQuality.hanCount(doc.body().text()))
    }

    @Test
    fun aFewLooseCharactersDoNotChangeHowAChapterIsRead() {
        val xhtml = "<html><body><h1>第一章</h1><p>甲段。</p><p>乙段。</p>完<br/></body></html>"
        assertEquals(listOf("第一章", "甲段。", "乙段。"), importedParagraphs(xhtml))
    }
}
