package com.example.scraper.epub

import com.example.scraper.engine.ChapterText
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapedChapterSummary
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading order for scraped chapters. */
object ChapterOrdering {
    /**
     * Scrape order, unless the chapters were clearly scraped out of order: every chapter has a
     * distinct title number and the numbers form one contiguous run (so volume restarts or gaps
     * never reorder anything).
     */
    fun order(chapters: List<ScrapedChapterSummary>): List<ScrapedChapterSummary> {
        val bySequence = chapters.sortedBy { it.sequence }
        if (bySequence.size < 2) return bySequence
        val numbers = bySequence.map { it.chapterNumber ?: return bySequence }
        if (numbers.toSet().size != numbers.size) return bySequence
        if (numbers.zipWithNext().all { (a, b) -> b > a }) return bySequence
        val span = numbers.max() - numbers.min() + 1
        if (span > bySequence.size * 1.5) return bySequence
        return bySequence.sortedWith(compareBy<ScrapedChapterSummary> { it.chapterNumber }.thenBy { it.sequence })
    }
}

/**
 * Writes an EPUB 3 (with an EPUB 2 NCX for older readers) from scraped chapters, streaming one
 * chapter at a time so large projects don't need to fit in memory.
 *
 * Chapter files are named after the stable chapter id (`text/c_<id>.xhtml`), so a library copy can
 * be refreshed later without changing the identity of chapters it already has.
 */
object ScrapedEpubBuilder {

    data class Result(val chapterCount: Int, val language: String)

    fun chapterHref(chapterId: String) = "text/c_$chapterId.xhtml"

    suspend fun write(
        project: ScrapeProject,
        chapters: List<ScrapedChapterSummary>,
        loadParagraphs: suspend (chapterId: String) -> List<String>,
        out: OutputStream,
        modified: Date = Date()
    ): Result {
        require(chapters.isNotEmpty()) { "There are no chapters to export yet" }
        val zip = ZipOutputStream(out)
        writeMimetype(zip)
        writeText(zip, "META-INF/container.xml", CONTAINER_XML)
        writeText(zip, "OEBPS/style.css", STYLE_CSS)

        val firstParagraphs = loadParagraphs(chapters.first().id)
        val language = languageFor(firstParagraphs.joinToString(" ").take(3000))
        val entries = ArrayList<Pair<ScrapedChapterSummary, String>>(chapters.size)
        for ((index, chapter) in chapters.withIndex()) {
            val paragraphs = if (index == 0) firstParagraphs else loadParagraphs(chapter.id)
            val title = chapter.title.ifBlank { "Chapter ${index + 1}" }
            entries += chapter to title
            writeText(zip, "OEBPS/${chapterHref(chapter.id)}", chapterXhtml(title, paragraphs, language))
        }
        val identifier = "urn:uuid:" + UUID.nameUUIDFromBytes(project.id.toByteArray(Charsets.UTF_8))
        writeText(zip, "OEBPS/nav.xhtml", navXhtml(project, entries, language))
        writeText(zip, "OEBPS/toc.ncx", tocNcx(project, entries, identifier))
        writeText(zip, "OEBPS/content.opf", contentOpf(project, entries, identifier, language, modified))
        zip.finish()
        zip.flush()
        return Result(chapters.size, language)
    }

    private fun languageFor(sample: String) = if (UrlIdentity.cjkRatio(sample, 3000) > 0.3) "zh" else "en"

    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeText(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path).apply { method = ZipEntry.DEFLATED }
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun esc(text: String) = ChapterText.escapeXml(text)

    private fun chapterXhtml(title: String, paragraphs: List<String>, language: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" lang=\"$language\">\n")
        append("<head>\n<meta charset=\"UTF-8\"/>\n<title>").append(esc(title)).append("</title>\n")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"../style.css\"/>\n</head>\n")
        append("<body>\n<section epub:type=\"chapter\">\n<h1>").append(esc(title)).append("</h1>\n")
        for (p in paragraphs) append("<p>").append(esc(p)).append("</p>\n")
        append("</section>\n</body>\n</html>\n")
    }

    private fun navXhtml(project: ScrapeProject, entries: List<Pair<ScrapedChapterSummary, String>>, language: String) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" lang=\"$language\">\n")
        append("<head>\n<meta charset=\"UTF-8\"/>\n<title>").append(esc(project.title)).append("</title>\n")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n</head>\n<body>\n")
        append("<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n<ol>\n")
        for ((chapter, title) in entries) {
            append("<li><a href=\"").append(chapterHref(chapter.id)).append("\">").append(esc(title)).append("</a></li>\n")
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }

    private fun tocNcx(project: ScrapeProject, entries: List<Pair<ScrapedChapterSummary, String>>, identifier: String) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n<head>\n")
        append("<meta name=\"dtb:uid\" content=\"").append(esc(identifier)).append("\"/>\n")
        append("<meta name=\"dtb:depth\" content=\"1\"/>\n<meta name=\"dtb:totalPageCount\" content=\"0\"/>\n")
        append("<meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n</head>\n")
        append("<docTitle><text>").append(esc(project.title)).append("</text></docTitle>\n")
        append("<docAuthor><text>").append(esc(project.author ?: "Unknown")).append("</text></docAuthor>\n<navMap>\n")
        entries.forEachIndexed { i, (chapter, title) ->
            append("<navPoint id=\"np").append(i + 1).append("\" playOrder=\"").append(i + 1).append("\">")
            append("<navLabel><text>").append(esc(title)).append("</text></navLabel>")
            append("<content src=\"").append(chapterHref(chapter.id)).append("\"/></navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    private fun contentOpf(
        project: ScrapeProject,
        entries: List<Pair<ScrapedChapterSummary, String>>,
        identifier: String,
        language: String,
        modified: Date
    ) = buildString {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"$language\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"bookid\">").append(esc(identifier)).append("</dc:identifier>\n")
        append("<dc:title>").append(esc(project.title)).append("</dc:title>\n")
        append("<dc:creator>").append(esc(project.author?.takeIf { it.isNotBlank() } ?: "Unknown")).append("</dc:creator>\n")
        append("<dc:language>").append(language).append("</dc:language>\n")
        append("<dc:description>").append(esc("Scraped from ${project.siteHost}. ${entries.size} chapters.")).append("</dc:description>\n")
        if (project.sourceUrl.isNotBlank()) append("<dc:source>").append(esc(project.sourceUrl)).append("</dc:source>\n")
        append("<meta property=\"dcterms:modified\">").append(utc.format(modified)).append("</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        for ((chapter, _) in entries) {
            append("<item id=\"c_").append(chapter.id).append("\" href=\"").append(chapterHref(chapter.id))
                .append("\" media-type=\"application/xhtml+xml\"/>\n")
        }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        for ((chapter, _) in entries) append("<itemref idref=\"c_").append(chapter.id).append("\"/>\n")
        append("</spine>\n</package>\n")
    }

    private const val CONTAINER_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
        "<rootfiles>\n<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n</rootfiles>\n" +
        "</container>\n"

    private const val STYLE_CSS = "body { font-family: serif; line-height: 1.6; margin: 0 5%; }\n" +
        "h1 { font-size: 1.4em; text-align: center; margin: 1em 0 1.2em; line-height: 1.3; }\n" +
        "p { margin: 0 0 0.8em; text-indent: 1.5em; text-align: justify; }\n"
}
