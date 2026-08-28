package com.example.epub

import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object EpubRebuilder {

    fun rebuild(
        originalEpub: ParsedEpub,
        chapters: List<ChapterEntity>,
        chunks: List<TranslationChunkEntity>,
        outputFile: File
    ) {
        // Pre-validation
        val preValidation = EpubValidator.validatePreExport(originalEpub, chapters, chunks)
        if (!preValidation.isValid) {
            throw IllegalStateException("EPUB export validation failed:\n" + preValidation.errors.joinToString("\n"))
        }

        // Map translations
        val metadataTitle = chunks.firstOrNull { it.chunkType == "TITLE" }?.translatedText
            ?: originalEpub.metadata.title
        val metadataDescription = chunks.firstOrNull { it.chunkType == "DESCRIPTION" }?.translatedText
            ?: originalEpub.metadata.description

        // Group chunks by chapterId and sort strictly by chunkOrder
        val chapterChunksMap = chunks.filter { it.chapterOrder >= 0 }
            .groupBy { it.chapterId }
            .mapValues { entry -> entry.value.sortedBy { it.chunkOrder } }

        val modifiedEntries = mutableMapOf<String, ByteArray>()

        // 1. Rebuild Chapter XHTML files strictly in spine order
        val sortedSpineChapters = chapters.sortedBy { it.chapterOrder }
        val chapterTitleMap = mutableMapOf<String, String>() // chapterId/href -> translated title

        for (chapterEntity in sortedSpineChapters) {
            val originalChapter = originalEpub.chapters.firstOrNull { it.chapterOrder == chapterEntity.chapterOrder }
                ?: continue

            val chapterChunks = chapterChunksMap[chapterEntity.id] ?: emptyList()
            val translatedTitle = chapterChunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }?.translatedText
                ?: chapterEntity.title
            chapterTitleMap[chapterEntity.originalHref] = translatedTitle
            chapterTitleMap[chapterEntity.id] = translatedTitle

            val bodyChunks = chapterChunks.filter { it.chunkType == "CHAPTER_BODY" }
            val translatedXhtml = rebuildChapterXhtml(originalChapter.rawXhtml, translatedTitle, bodyChunks)
            modifiedEntries[originalChapter.fullPathInZip] = translatedXhtml.toByteArray(Charsets.UTF_8)
        }

        // 2. Rebuild OPF with translated metadata
        val translatedOpf = rebuildOpf(originalEpub, metadataTitle, metadataDescription)
        modifiedEntries[originalEpub.opfPath] = translatedOpf.toByteArray(Charsets.UTF_8)

        // 3. Rebuild NCX if present
        if (originalEpub.ncxHref != null) {
            val fullNcxPath = if (originalEpub.opfDirectory.isBlank()) originalEpub.ncxHref else "${originalEpub.opfDirectory}/${originalEpub.ncxHref}"
            val originalNcxEntry = ZipFile(originalEpub.originalFile).use { it.getEntry(fullNcxPath) }
            if (originalNcxEntry != null) {
                val originalNcxXml = ZipFile(originalEpub.originalFile).use { it.getInputStream(originalNcxEntry).bufferedReader().readText() }
                val translatedNcx = rebuildNcx(originalNcxXml, metadataTitle, chapterTitleMap)
                modifiedEntries[fullNcxPath] = translatedNcx.toByteArray(Charsets.UTF_8)
            }
        }

        // 4. Rebuild Nav XHTML if present
        if (originalEpub.navHref != null) {
            val fullNavPath = if (originalEpub.opfDirectory.isBlank()) originalEpub.navHref else "${originalEpub.opfDirectory}/${originalEpub.navHref}"
            val originalNavEntry = ZipFile(originalEpub.originalFile).use { it.getEntry(fullNavPath) }
            if (originalNavEntry != null) {
                val originalNavXml = ZipFile(originalEpub.originalFile).use { it.getInputStream(originalNavEntry).bufferedReader().readText() }
                val translatedNav = rebuildNav(originalNavXml, chapterTitleMap)
                modifiedEntries[fullNavPath] = translatedNav.toByteArray(Charsets.UTF_8)
            }
        }

        // 5. Assemble ZIP EPUB
        writeEpubZip(originalEpub.originalFile, modifiedEntries, outputFile)

        // Post-validation
        val postValidation = EpubValidator.validateFinalEpub(outputFile)
        if (!postValidation.isValid) {
            outputFile.delete()
            throw IllegalStateException("Final EPUB validation failed:\n" + postValidation.errors.joinToString("\n"))
        }
    }

    private fun rebuildChapterXhtml(
        rawXhtml: String,
        translatedTitle: String,
        bodyChunks: List<TranslationChunkEntity>
    ): String {
        val doc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // Update Title and Main Header
        doc.title(translatedTitle)
        val heading = doc.select("h1, h2, h3, .chapter-title, .title").firstOrNull { it.text().isNotBlank() }
        if (heading != null) {
            heading.text(translatedTitle)
        }

        // Collect all translated body text paragraphs
        val translatedParagraphs = mutableListOf<String>()
        for (chunk in bodyChunks) {
            val text = chunk.translatedText ?: chunk.sourceText
            // Split chunk by double newline
            val parts = text.split(Regex("\n\n+"))
            translatedParagraphs.addAll(parts.map { it.trim() }.filter { it.isNotBlank() })
        }

        // Replace translatable paragraph elements in DOM
        val pElements = doc.select("p, blockquote, li, dt, dd")
        if (pElements.isNotEmpty() && translatedParagraphs.isNotEmpty()) {
            val limit = minOf(pElements.size, translatedParagraphs.size)
            for (i in 0 until limit) {
                pElements[i].text(translatedParagraphs[i])
            }
            // If there are more translated paragraphs than original elements, append to body
            if (translatedParagraphs.size > pElements.size) {
                val body = doc.body()
                if (body != null) {
                    for (i in pElements.size until translatedParagraphs.size) {
                        body.appendElement("p").text(translatedParagraphs[i])
                    }
                }
            }
        } else if (translatedParagraphs.isNotEmpty()) {
            val body = doc.body()
            if (body != null) {
                body.empty()
                if (heading != null) {
                    body.appendElement("h1").text(translatedTitle)
                }
                for (p in translatedParagraphs) {
                    body.appendElement("p").text(p)
                }
            }
        }

        return doc.html()
    }

    private fun rebuildOpf(
        originalEpub: ParsedEpub,
        translatedTitle: String,
        translatedDescription: String
    ): String {
        val zip = ZipFile(originalEpub.originalFile)
        val opfXml = zip.use { z ->
            val entry = z.getEntry(originalEpub.opfPath)
            z.getInputStream(entry).bufferedReader().readText()
        }

        val doc = Jsoup.parse(opfXml, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // Update Title
        val titleElem = doc.select("metadata > dc\\:title, metadata > title").first()
        if (titleElem != null) {
            titleElem.text(translatedTitle)
        } else {
            doc.select("metadata").first()?.appendElement("dc:title")?.text(translatedTitle)
        }

        // Update Description
        val descElem = doc.select("metadata > dc\\:description, metadata > description").first()
        if (descElem != null) {
            descElem.text(translatedDescription)
        } else if (translatedDescription.isNotBlank()) {
            doc.select("metadata").first()?.appendElement("dc:description")?.text(translatedDescription)
        }

        // Update Language to English
        val langElem = doc.select("metadata > dc\\:language, metadata > language").first()
        if (langElem != null) {
            langElem.text("en")
        }

        return doc.html()
    }

    private fun rebuildNcx(
        rawNcx: String,
        translatedBookTitle: String,
        chapterTitleMap: Map<String, String>
    ): String {
        val doc = Jsoup.parse(rawNcx, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // Update docTitle
        doc.select("docTitle > text").first()?.text(translatedBookTitle)

        // Update navPoints
        for (navPoint in doc.select("navPoint")) {
            val contentSrc = navPoint.select("content").attr("src").substringBefore('#')
            val translated = chapterTitleMap[contentSrc]
                ?: chapterTitleMap.entries.firstOrNull { contentSrc.endsWith(it.key) || it.key.endsWith(contentSrc) }?.value
            if (translated != null) {
                navPoint.select("navLabel > text").first()?.text(translated)
            }
        }

        return doc.html()
    }

    private fun rebuildNav(
        rawNav: String,
        chapterTitleMap: Map<String, String>
    ): String {
        val doc = Jsoup.parse(rawNav, "", Parser.htmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        for (a in doc.select("nav a[href]")) {
            val href = a.attr("href").substringBefore('#')
            val translated = chapterTitleMap[href]
                ?: chapterTitleMap.entries.firstOrNull { href.endsWith(it.key) || it.key.endsWith(href) }?.value
            if (translated != null) {
                a.text(translated)
            }
        }

        return doc.html()
    }

    private fun writeEpubZip(
        originalFile: File,
        modifiedEntries: Map<String, ByteArray>,
        outputFile: File
    ) {
        if (outputFile.exists()) outputFile.delete()

        val origZip = ZipFile(originalFile)
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. Write 'mimetype' uncompressed first (Standard EPUB Requirement)
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().apply { update(mimeBytes) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. Write all other entries
            val origEntries = origZip.entries()
            val writtenEntryNames = mutableSetOf("mimetype")

            while (origEntries.hasMoreElements()) {
                val entry = origEntries.nextElement()
                val entryName = entry.name.replace('\\', '/')
                if (entryName == "mimetype" || entry.isDirectory) continue

                val newEntry = ZipEntry(entryName).apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(newEntry)

                if (modifiedEntries.containsKey(entryName)) {
                    val bytes = modifiedEntries[entryName]!!
                    zos.write(bytes)
                } else {
                    origZip.getInputStream(entry).use { input ->
                        input.copyTo(zos)
                    }
                }
                zos.closeEntry()
                writtenEntryNames.add(entryName)
            }

            // Write any modified entries that were not in the original zip (if any)
            for ((name, bytes) in modifiedEntries) {
                if (!writtenEntryNames.contains(name)) {
                    val newEntry = ZipEntry(name).apply {
                        method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(newEntry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        origZip.close()
    }
}
