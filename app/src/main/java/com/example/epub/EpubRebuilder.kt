package com.example.epub

import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object EpubRebuilder {

    private const val TAG = "EpubRebuilder"

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "novel" }
    }

    /**
     * Atomically rebuilds an English EPUB from the original source file and database translations.
     * Uses constant O(1) memory streaming so that novels with hundreds or thousands of chapters (e.g. 315+ chapters)
     * rebuild quickly without OutOfMemoryError.
     *
     * Creates a temporary file `[outputFile].tmp` first, streams and validates it completely,
     * and only moves it to `outputFile` upon successful validation.
     */
    suspend fun rebuild(
        sourceEpubFile: File,
        bookId: String,
        jobId: String,
        database: AppDatabase,
        outputFile: File
    ) {
        if (!sourceEpubFile.exists() || sourceEpubFile.length() == 0L) {
            throw IllegalArgumentException("Source EPUB file not found or empty: ${sourceEpubFile.absolutePath}")
        }

        val parentDir = outputFile.parentFile ?: sourceEpubFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempOutputFile = File(parentDir, "${outputFile.name}.tmp")
        if (tempOutputFile.exists()) {
            tempOutputFile.delete()
        }

        try {
            buildEpubToTempFile(
                sourceEpubFile = sourceEpubFile,
                bookId = bookId,
                jobId = jobId,
                database = database,
                tempOutputFile = tempOutputFile
            )

            // Post-construction validation
            val chapters = database.chapterDao().getChaptersByBook(bookId)
            val book = database.bookDao().getBookById(bookId)
            val validation = EpubValidator.validateFinalEpub(
                exportedFile = tempOutputFile,
                expectedChapterCount = chapters.size,
                hadOriginalCover = book?.coverPath != null
            )

            if (!validation.isValid) {
                tempOutputFile.delete()
                throw IllegalStateException("EPUB export validation failed:\n" + validation.errors.joinToString("\n"))
            }

            // Atomic move to final destination
            if (outputFile.exists()) {
                outputFile.delete()
            }
            val moved = tempOutputFile.renameTo(outputFile)
            if (!moved) {
                // Fallback copy if cross-filesystem rename fails
                tempOutputFile.inputStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempOutputFile.delete()
            }

            Log.i(TAG, "Successfully built and validated English EPUB at: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            if (tempOutputFile.exists()) {
                tempOutputFile.delete()
            }
            Log.e(TAG, "EPUB export failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun buildEpubToTempFile(
        sourceEpubFile: File,
        bookId: String,
        jobId: String,
        database: AppDatabase,
        tempOutputFile: File
    ) {
        val sourceZip = ZipFile(sourceEpubFile)
        try {
            // 1. Locate container.xml and find OPF path
            val containerEntry = sourceZip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found")
            val containerXml = sourceZip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val rootfile = containerDoc.select("rootfiles > rootfile").first()
                ?: throw IllegalArgumentException("Invalid container.xml: No rootfile defined")

            val opfPath = rootfile.attr("full-path").replace('\\', '/')
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""

            // 2. Parse OPF to identify metadata, manifest items, spine, and navigation
            val opfEntry = EpubParser.findZipEntry(sourceZip, opfPath)
                ?: throw IllegalArgumentException("OPF file not found at: $opfPath")
            val originalOpfXml = sourceZip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val opfDoc = Jsoup.parse(originalOpfXml, "", Parser.xmlParser())

            val manifestMap = mutableMapOf<String, ManifestItem>()
            for (item in opfDoc.select("manifest > item, opf\\:manifest > opf\\:item, item")) {
                val id = item.attr("id")
                val href = item.attr("href")
                val mediaType = item.attr("media-type")
                val props = item.attr("properties")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifestMap[id] = ManifestItem(id, href, mediaType, props)
                }
            }

            // Identify cover using the multi-strategy lookup
            val coverInfo = EpubParser.findCoverInfo(opfDoc, manifestMap, opfDir, sourceZip)

            // Identify NCX and Nav paths
            val spineToc = opfDoc.select("spine, opf\\:spine").first()?.attr("toc")
            val ncxHref = if (!spineToc.isNullOrBlank()) manifestMap[spineToc]?.href else manifestMap.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href
            val fullNcxPath = if (!ncxHref.isNullOrBlank()) EpubParser.resolveZipPath(opfDir, ncxHref) else null

            val navHref = manifestMap.values.firstOrNull { it.properties?.contains("nav", ignoreCase = true) == true }?.href
            val fullNavPath = if (!navHref.isNullOrBlank()) EpubParser.resolveZipPath(opfDir, navHref) else null

            // 3. Load Book Metadata and Chapter Entities from DB
            val book = database.bookDao().getBookById(bookId)
            val chapters = database.chapterDao().getChaptersByBook(bookId)

            // Map chapter Hrefs and Paths
            val chapterPathToEntityMap = mutableMapOf<String, ChapterEntity>()
            val chapterTitleMap = mutableMapOf<String, String>() // href/id -> translated title

            // Batch fetch all chapter title chunks in a single query to minimize memory and DB roundtrips
            val chapterTitleChunks = database.chunkDao().getChapterTitleChunks(jobId).associateBy { it.chapterId }

            for (chapter in chapters) {
                val fullPath = EpubParser.resolveZipPath(opfDir, chapter.originalHref)
                chapterPathToEntityMap[fullPath] = chapter
                chapterPathToEntityMap[chapter.originalHref] = chapter

                // Try to find translated chapter title
                val titleChunk = chapterTitleChunks[chapter.id]
                val translatedTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() } ?: chapter.title
                chapterTitleMap[chapter.originalHref] = translatedTitle
                chapterTitleMap[chapter.id] = translatedTitle
                chapterTitleMap[fullPath] = translatedTitle
            }

            // Get translated Book Title and Description
            val translatedBookTitle = database.chunkDao().getTitleChunk(jobId)?.translatedText?.takeIf { it.isNotBlank() }
                ?: book?.title ?: opfDoc.select("metadata > dc\\:title, metadata > title").text().ifBlank { "Untitled" }

            val translatedBookDesc = database.chunkDao().getDescriptionChunk(jobId)?.translatedText?.takeIf { it.isNotBlank() }
                ?: book?.description ?: opfDoc.select("metadata > dc\\:description, metadata > description").text()

            // 4. Stream Zip Writing directly to temporary file
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutputFile), 64 * 1024)).use { zos ->
                // Step A: Write 'mimetype' uncompressed first
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

                val writtenEntries = mutableSetOf("mimetype")
                val origEntries = sourceZip.entries()

                while (origEntries.hasMoreElements()) {
                    val entry = origEntries.nextElement()
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName == "mimetype" || entry.isDirectory) continue

                    val cleanEntryName = entryName.trimStart('/')

                    when {
                        // 1. OPF Entry -> Rebuild OPF with English metadata and preserved cover
                        cleanEntryName == opfPath || cleanEntryName.equals(opfPath, ignoreCase = true) -> {
                            val rebuiltOpf = rebuildOpfXml(
                                originalOpfXml = originalOpfXml,
                                translatedTitle = translatedBookTitle,
                                translatedDesc = translatedBookDesc,
                                coverInfo = coverInfo
                            )
                            writeZipEntry(zos, cleanEntryName, rebuiltOpf.toByteArray(Charsets.UTF_8))
                            writtenEntries.add(cleanEntryName)
                        }

                        // 2. NCX TOC Entry -> Rebuild NCX with translated title & chapters
                        fullNcxPath != null && (cleanEntryName == fullNcxPath || cleanEntryName.equals(fullNcxPath, ignoreCase = true)) -> {
                            val origNcxXml = sourceZip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val rebuiltNcx = rebuildNcxXml(origNcxXml, translatedBookTitle, chapterTitleMap)
                            writeZipEntry(zos, cleanEntryName, rebuiltNcx.toByteArray(Charsets.UTF_8))
                            writtenEntries.add(cleanEntryName)
                        }

                        // 3. EPUB3 Nav Entry -> Rebuild Nav with translated titles
                        fullNavPath != null && (cleanEntryName == fullNavPath || cleanEntryName.equals(fullNavPath, ignoreCase = true)) -> {
                            val origNavXml = sourceZip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val rebuiltNav = rebuildNavXml(origNavXml, chapterTitleMap)
                            writeZipEntry(zos, cleanEntryName, rebuiltNav.toByteArray(Charsets.UTF_8))
                            writtenEntries.add(cleanEntryName)
                        }

                        // 4. Chapter XHTML in Spine -> Stream and rebuild chapter XHTML
                        chapterPathToEntityMap.containsKey(cleanEntryName) || isChapterEntry(cleanEntryName, chapterPathToEntityMap) -> {
                            val chapterEntity = chapterPathToEntityMap[cleanEntryName]
                                ?: findMatchingChapter(cleanEntryName, chapterPathToEntityMap)

                            if (chapterEntity != null) {
                                val rawChapterXhtml = sourceZip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                                val chapterChunks = database.chunkDao().getChunksByJobAndChapter(jobId, chapterEntity.id)
                                val translatedChapterTitle = chapterTitleMap[chapterEntity.originalHref] ?: chapterEntity.title

                                val rebuiltXhtml = rebuildChapterXhtml(
                                    rawXhtml = rawChapterXhtml,
                                    translatedTitle = translatedChapterTitle,
                                    chapterChunks = chapterChunks
                                )
                                writeZipEntry(zos, cleanEntryName, rebuiltXhtml.toByteArray(Charsets.UTF_8))
                            } else {
                                // Fallback copy if not mapped
                                streamCopyZipEntry(zos, sourceZip, entry, cleanEntryName)
                            }
                            writtenEntries.add(cleanEntryName)
                        }

                        // 5. All other resources (Cover image, pictures, CSS, fonts, audio) -> Stream directly
                        else -> {
                            streamCopyZipEntry(zos, sourceZip, entry, cleanEntryName)
                            writtenEntries.add(cleanEntryName)
                        }
                    }
                }

                // Ensure cover file is explicitly present in output ZIP if not already written
                if (coverInfo != null) {
                    val coverPath = coverInfo.fullPath
                    val alreadyWritten = writtenEntries.contains(coverPath) ||
                            writtenEntries.any { it.equals(coverPath, ignoreCase = true) }
                    if (!alreadyWritten) {
                        val coverEntry = EpubParser.findZipEntry(sourceZip, coverPath)
                        if (coverEntry != null) {
                            streamCopyZipEntry(zos, sourceZip, coverEntry, coverPath)
                            writtenEntries.add(coverPath)
                        } else if (book?.coverPath != null) {
                            val localCoverFile = File(book.coverPath)
                            if (localCoverFile.exists() && localCoverFile.length() > 0) {
                                val entry = ZipEntry(coverPath).apply { method = ZipEntry.DEFLATED }
                                zos.putNextEntry(entry)
                                localCoverFile.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                                writtenEntries.add(coverPath)
                            }
                        }
                    }
                }
            }
        } finally {
            sourceZip.close()
        }
    }

    private fun isChapterEntry(entryName: String, chapterMap: Map<String, ChapterEntity>): Boolean {
        if (chapterMap.containsKey(entryName)) return true
        val decoded = try { URLDecoder.decode(entryName, "UTF-8") } catch (_: Exception) { entryName }
        return chapterMap.containsKey(decoded) || chapterMap.keys.any { it.endsWith(entryName) || entryName.endsWith(it) }
    }

    private fun findMatchingChapter(entryName: String, chapterMap: Map<String, ChapterEntity>): ChapterEntity? {
        val direct = chapterMap[entryName]
        if (direct != null) return direct

        val decoded = try { URLDecoder.decode(entryName, "UTF-8") } catch (_: Exception) { entryName }
        val fromDecoded = chapterMap[decoded]
        if (fromDecoded != null) return fromDecoded

        return chapterMap.entries.firstOrNull { entryName.endsWith(it.key) || it.key.endsWith(entryName) }?.value
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, data: ByteArray) {
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun streamCopyZipEntry(zos: ZipOutputStream, sourceZip: ZipFile, origEntry: ZipEntry, entryName: String) {
        val newEntry = ZipEntry(entryName).apply {
            method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(newEntry)
        sourceZip.getInputStream(origEntry).use { input ->
            input.copyTo(zos, bufferSize = 32 * 1024)
        }
        zos.closeEntry()
    }

    private fun rebuildChapterXhtml(
        rawXhtml: String,
        translatedTitle: String,
        chapterChunks: List<TranslationChunkEntity>
    ): String {
        val doc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // 1. Update Title and Heading
        if (translatedTitle.isNotBlank()) {
            doc.title(translatedTitle)
            val heading = doc.select("h1, h2, h3, .chapter-title, .title").firstOrNull { it.text().isNotBlank() }
            if (heading != null) {
                heading.text(translatedTitle)
            }
        }

        // 2. Collect translated body paragraphs
        val bodyChunks = chapterChunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
        val translatedParagraphs = mutableListOf<String>()

        for (chunk in bodyChunks) {
            val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: chunk.sourceText
            val parts = text.split(Regex("\n\n+"))
            for (p in parts) {
                val trimmed = p.trim()
                if (trimmed.isNotBlank()) {
                    translatedParagraphs.add(trimmed)
                }
            }
        }

        // 3. Replace text in existing paragraph elements without deleting structure or images
        val pElements = doc.select("p, blockquote, li, dt, dd")
        if (pElements.isNotEmpty() && translatedParagraphs.isNotEmpty()) {
            val limit = minOf(pElements.size, translatedParagraphs.size)
            for (i in 0 until limit) {
                pElements[i].text(translatedParagraphs[i])
            }
            // Append extra paragraphs if translation generated more paragraphs than original elements
            if (translatedParagraphs.size > pElements.size) {
                val targetContainer = pElements.lastOrNull()?.parent() ?: doc.body()
                if (targetContainer != null) {
                    for (i in pElements.size until translatedParagraphs.size) {
                        targetContainer.appendElement("p").text(translatedParagraphs[i])
                    }
                }
            }
        } else if (translatedParagraphs.isNotEmpty()) {
            val body = doc.body()
            if (body != null) {
                // If there are no existing p elements, append without destroying images/illustrations
                for (p in translatedParagraphs) {
                    body.appendElement("p").text(p)
                }
            }
        }

        return doc.html()
    }

    private fun rebuildOpfXml(
        originalOpfXml: String,
        translatedTitle: String,
        translatedDesc: String,
        coverInfo: EpubParser.CoverInfo?
    ): String {
        val doc = Jsoup.parse(originalOpfXml, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // Update Title
        val titleElem = doc.select("metadata > dc\\:title, metadata > title, opf\\:metadata > dc\\:title").first()
        if (titleElem != null) {
            titleElem.text(translatedTitle)
        } else {
            val meta = doc.select("metadata, opf\\:metadata").first()
            val tagName = if (meta?.tagName()?.contains("opf:") == true) "dc:title" else "dc:title"
            meta?.appendElement(tagName)?.text(translatedTitle)
        }

        // Update Description
        val descElem = doc.select("metadata > dc\\:description, metadata > description, opf\\:metadata > dc\\:description").first()
        if (descElem != null) {
            descElem.text(translatedDesc)
        } else if (translatedDesc.isNotBlank()) {
            val meta = doc.select("metadata, opf\\:metadata").first()
            meta?.appendElement("dc:description")?.text(translatedDesc)
        }

        // Update Language to English
        val langElem = doc.select("metadata > dc\\:language, metadata > language, opf\\:metadata > dc\\:language").first()
        if (langElem != null) {
            langElem.text("en")
        } else {
            val meta = doc.select("metadata, opf\\:metadata").first()
            meta?.appendElement("dc:language")?.text("en")
        }

        // Ensure Cover metadata & manifest properties are preserved
        if (coverInfo != null) {
            val coverItemId = coverInfo.itemId

            // 1. EPUB2: <meta name="cover" content="cover_item_id"/>
            val metaCover = doc.select("metadata > meta[name=cover], opf\\:metadata > opf\\:meta[name=cover], meta[name=cover]").first()
            if (metaCover == null) {
                val metaContainer = doc.select("metadata, opf\\:metadata").first()
                if (metaContainer != null) {
                    val tagName = if (metaContainer.tagName().contains("opf:")) "opf:meta" else "meta"
                    metaContainer.appendElement(tagName)
                        .attr("name", "cover")
                        .attr("content", coverItemId)
                }
            } else {
                metaCover.attr("content", coverItemId)
            }

            // 2. EPUB3: properties="cover-image" on manifest item
            val manifestItem = doc.select("manifest > item, opf\\:manifest > opf\\:item, item").firstOrNull { it.attr("id") == coverItemId }
            if (manifestItem != null) {
                val currentProps = manifestItem.attr("properties")
                if (!currentProps.contains("cover-image", ignoreCase = true)) {
                    val newProps = if (currentProps.isBlank()) "cover-image" else "$currentProps cover-image"
                    manifestItem.attr("properties", newProps)
                }
                // Ensure media-type is an image media type
                if (manifestItem.attr("media-type").isBlank() && coverInfo.mediaType.isNotBlank()) {
                    manifestItem.attr("media-type", coverInfo.mediaType)
                }
            }
        }

        return doc.html()
    }

    private fun rebuildNcxXml(
        originalNcxXml: String,
        translatedBookTitle: String,
        chapterTitleMap: Map<String, String>
    ): String {
        val doc = Jsoup.parse(originalNcxXml, "", Parser.xmlParser())
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

    private fun rebuildNavXml(
        originalNavXml: String,
        chapterTitleMap: Map<String, String>
    ): String {
        val doc = Jsoup.parse(originalNavXml, "", Parser.htmlParser())
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

    private fun findZipEntry(zip: ZipFile, path: String): ZipEntry? {
        val direct = zip.getEntry(path)
        if (direct != null) return direct

        try {
            val decoded = URLDecoder.decode(path, "UTF-8")
            val decodedEntry = zip.getEntry(decoded)
            if (decodedEntry != null) return decodedEntry
        } catch (_: Exception) {}

        val clean = path.replace('\\', '/')
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val entryName = entry.name.replace('\\', '/')
            if (entryName.equals(clean, ignoreCase = true)) {
                return entry
            }
        }
        return null
    }

    private fun resolveZipPath(baseDir: String, href: String): String {
        val cleanHref = href.replace('\\', '/')
        if (baseDir.isBlank()) return cleanHref
        val combined = "$baseDir/$cleanHref"
        val parts = combined.split('/')
        val stack = mutableListOf<String>()
        for (part in parts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
