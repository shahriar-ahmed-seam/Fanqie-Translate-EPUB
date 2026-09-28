package com.example.scraper

import com.example.scraper.epub.ChapterOrdering
import com.example.scraper.epub.ScrapedEpubBuilder
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapeStatus
import com.example.scraper.model.ScrapedChapterSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapedEpubBuilderTest {

    private val project = ScrapeProject(
        id = "p1", title = "Tom & Jerry's <Adventure>", author = null, siteHost = "site.com",
        sourceUrl = "https://site.com/n/chapter-1?a=1&b=2", lastChapterUrl = null, resumeUrl = null,
        status = ScrapeStatus.COMPLETED, chapterCount = 3, totalCharacters = 0, lastError = null,
        linkedBookId = null, linkedChapterCount = 0, createdAt = 0, updatedAt = 0
    )

    private fun chapter(id: String, seq: Int, title: String, number: Double? = null) =
        ScrapedChapterSummary(id, "p1", seq, number, title, "https://site.com/n/$id", 100, 2, 0)

    private val chapters = listOf(
        chapter("aa01", 1, "Chapter 1: Start"),
        chapter("aa02", 2, "Chapter 2: \"Quotes\" & <tags>"),
        chapter("aa03", 3, "")
    )

    private val paragraphs = mapOf(
        "aa01" to listOf("First & only <b>not bold</b>", "Second"),
        "aa02" to listOf("Line with \u0007 control char"),
        "aa03" to emptyList()
    )

    private fun build(): ByteArray = ByteArrayOutputStream().also { out ->
        runBlocking { ScrapedEpubBuilder.write(project, chapters, { paragraphs.getValue(it) }, out) }
    }.toByteArray()

    @Test
    fun mimetypeIsTheFirstUncompressedEntry() {
        ZipInputStream(ByteArrayInputStream(build())).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun everyXmlFileIsWellFormedAndChaptersUseStableNames() {
        val file = File.createTempFile("scraped", ".epub").apply { writeBytes(build()) }
        try {
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue(names.containsAll(listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/style.css")))
                assertTrue(names.containsAll(listOf("OEBPS/text/c_aa01.xhtml", "OEBPS/text/c_aa02.xhtml", "OEBPS/text/c_aa03.xhtml")))
                val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                for (name in names.filter { it.endsWith(".xml") || it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") }) {
                    val bytes = zip.getInputStream(zip.getEntry(name)).readBytes()
                    assertTrue("$name must start with the XML declaration", bytes.toString(Charsets.UTF_8).startsWith("<?xml"))
                    factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
                }
                val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8)
                assertTrue(opf.contains("<dc:title>Tom &amp; Jerry&#39;s &lt;Adventure&gt;</dc:title>"))
                assertTrue(opf.contains("<dc:language>en</dc:language>"))
                assertTrue(Regex("<meta property=\"dcterms:modified\">\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</meta>").containsMatchIn(opf))
                val spine = Regex("<itemref idref=\"(c_[a-z0-9]+)\"/>").findAll(opf).map { it.groupValues[1] }.toList()
                assertEquals(listOf("c_aa01", "c_aa02", "c_aa03"), spine)
                val chapter3 = zip.getInputStream(zip.getEntry("OEBPS/text/c_aa03.xhtml")).readBytes().toString(Charsets.UTF_8)
                assertTrue("blank titles get a numbered fallback", chapter3.contains("<h1>Chapter 3</h1>"))
                val chapter1 = zip.getInputStream(zip.getEntry("OEBPS/text/c_aa01.xhtml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(chapter1.contains("<p>First &amp; only &lt;b&gt;not bold&lt;/b&gt;</p>"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun chineseContentIsTaggedAsChinese() {
        val out = ByteArrayOutputStream()
        runBlocking {
            ScrapedEpubBuilder.write(project, listOf(chapter("zz01", 1, "第一章")), { listOf("这是中文内容。".repeat(20)) }, out)
        }
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "OEBPS/content.opf") entry = zip.nextEntry
            assertTrue(zip.readBytes().toString(Charsets.UTF_8).contains("<dc:language>zh</dc:language>"))
        }
    }

    @Test
    fun orderingKeepsScrapeOrderUnlessChaptersWereClearlyScrapedOutOfOrder() {
        val inOrder = listOf(chapter("a", 1, "Chapter 1", 1.0), chapter("b", 2, "Chapter 2", 2.0))
        assertEquals(listOf("a", "b"), ChapterOrdering.order(inOrder).map { it.id })

        val outOfOrder = listOf(chapter("c5", 1, "Ch 5", 5.0), chapter("c6", 2, "Ch 6", 6.0), chapter("c1", 3, "Ch 1", 1.0), chapter("c2", 4, "Ch 2", 2.0), chapter("c3", 5, "Ch 3", 3.0), chapter("c4", 6, "Ch 4", 4.0))
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), ChapterOrdering.order(outOfOrder).map { it.id })

        val volumeRestart = listOf(chapter("v1a", 1, "Ch 48", 48.0), chapter("v1b", 2, "Ch 49", 49.0), chapter("v2a", 3, "Ch 1", 1.0), chapter("v2b", 4, "Ch 2", 2.0))
        assertEquals("gaps mean numbering restarted; keep scrape order", listOf("v1a", "v1b", "v2a", "v2b"), ChapterOrdering.order(volumeRestart).map { it.id })

        val missingNumbers = listOf(chapter("x", 2, "Prologue"), chapter("y", 1, "Chapter 1", 1.0))
        assertEquals(listOf("y", "x"), ChapterOrdering.order(missingNumbers).map { it.id })
    }

    /** Writes a sample book to build/epub-samples so it can be checked with W3C epubcheck. */
    @Test
    fun writesASampleForEpubcheck() {
        val dir = File("build/epub-samples").apply { mkdirs() }
        val file = File(dir, "scraped-sample.epub")
        file.writeBytes(build())
        assertTrue(file.length() > 500)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToBuildAnEmptyBook() {
        runBlocking { ScrapedEpubBuilder.write(project, emptyList(), { emptyList() }, ByteArrayOutputStream()) }
    }
}
