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
}
