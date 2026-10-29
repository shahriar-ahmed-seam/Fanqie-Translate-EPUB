package com.example.epub

import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.translation.TranslationQuality
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
        outputFile: File,
        selectedChapterIds: Set<String>? = null,
        customTitle: String? = null
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
            val chaptersToExport = buildEpubToTempFile(
                sourceEpubFile = sourceEpubFile,
                bookId = bookId,
                jobId = jobId,
                database = database,
                tempOutputFile = tempOutputFile,
                selectedChapterIds = selectedChapterIds,
                customTitle = customTitle
            )

            // Post-construction validation
            val book = database.bookDao().getBookById(bookId)
            val validation = EpubValidator.validateFinalEpub(
                exportedFile = tempOutputFile,
                expectedChapterCount = chaptersToExport.size,
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
        tempOutputFile: File,
        selectedChapterIds: Set<String>? = null,
        customTitle: String? = null
    ): List<ChapterEntity> {
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
            val allChapters = database.chapterDao().getChaptersByBook(bookId)
            val chaptersToExport = if (!selectedChapterIds.isNullOrEmpty()) {
                allChapters.filter { selectedChapterIds.contains(it.id) }.sortedBy { it.chapterOrder }
            } else {
                allChapters.sortedBy { it.chapterOrder }
            }

            // The archive's real entry names. Chapters are matched to the entry they were read from at
            // import (URL-encoded or differently cased hrefs used to leave whole chapters in Chinese).
            val entryIndex = ZipEntryIndex(sourceZip)
            val opfEntryName = opfEntry.name.replace('\\', '/').trimStart('/')
            val ncxEntryName = fullNcxPath?.let { entryIndex.find(it) ?: it }
            val navEntryName = fullNavPath?.let { entryIndex.find(it) ?: it }

            val allOriginalChapterPaths = allChapters.flatMap {
                listOfNotNull(it.originalHref, EpubParser.resolveZipPath(opfDir, it.originalHref), entryIndex.resolve(opfDir, it.originalHref))
            }.toSet()
            val allChaptersByHref = allChapters.associateBy { it.originalHref }

            // Map chapter Hrefs and Paths
            val chapterPathToEntityMap = mutableMapOf<String, ChapterEntity>()
            val chapterTitleMap = mutableMapOf<String, String>() // href/id -> title for the table of contents
            val translatedChapterTitles = mutableMapOf<String, String>() // chapter id -> real translation only

            // Batch fetch all chapter title chunks in a single query to minimize memory and DB roundtrips
            val chapterTitleChunks = database.chunkDao().getChapterTitleChunks(jobId).associateBy { it.chapterId }

            for (chapter in chaptersToExport) {
                val fullPath = EpubParser.resolveZipPath(opfDir, chapter.originalHref)
                chapterPathToEntityMap[fullPath] = chapter
                chapterPathToEntityMap[chapter.originalHref] = chapter
                entryIndex.resolve(opfDir, chapter.originalHref)?.let { chapterPathToEntityMap[it] = chapter }

                // Translated chapter title; an untranslated (Chinese) one shows as "Chapter N" like in the reader
                val titleChunk = chapterTitleChunks[chapter.id]
                val translated = TranslationQuality.usableTitle(titleChunk?.translatedText, titleChunk?.sourceText)
                if (translated != null) translatedChapterTitles[chapter.id] = translated
                val tocTitle = translated ?: fallbackChapterTitle(chapter)
                chapterTitleMap[chapter.originalHref] = tocTitle
                chapterTitleMap[chapter.id] = tocTitle
                chapterTitleMap[fullPath] = tocTitle
                chapterTitleMap[hrefKey(fullPath)] = tocTitle
            }

            // Get translated Book Title and Description
            val originalBookTitle = opfDoc.select("metadata > dc\\:title, metadata > title").text().ifBlank { book?.title.orEmpty() }
            val titleChunk = database.chunkDao().getTitleChunk(jobId)
            val baseTitle = TranslationQuality.usableTitle(titleChunk?.translatedText, titleChunk?.sourceText)
                ?: book?.title ?: opfDoc.select("metadata > dc\\:title, metadata > title").text().ifBlank { "Untitled" }
            val translatedBookTitle = customTitle ?: baseTitle

            val descChunk = database.chunkDao().getDescriptionChunk(jobId)
            val translatedBookDesc = TranslationQuality.usable(descChunk?.translatedText, descChunk?.sourceText)
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
                        // 1. OPF Entry -> Rebuild OPF with English metadata and preserved cover & filtered spine
                        cleanEntryName == opfEntryName || cleanEntryName == opfPath || cleanEntryName.equals(opfPath, ignoreCase = true) -> {
                            val rebuiltOpf = rebuildOpfXml(
                                originalOpfXml = originalOpfXml,
                                translatedTitle = translatedBookTitle,
                                translatedDesc = translatedBookDesc,
                                coverInfo = coverInfo,
                                chaptersToExport = if (!selectedChapterIds.isNullOrEmpty()) chaptersToExport else null,
                                manifestMap = manifestMap,
                                opfDir = opfDir
                            )
                            writeZipEntry(zos, cleanEntryName, rebuiltOpf.toByteArray(Charsets.UTF_8))
                            writtenEntries.add(cleanEntryName)
                        }

                        // 2. NCX TOC Entry -> Rebuild NCX with translated title & chapters
                        fullNcxPath != null && (cleanEntryName == ncxEntryName || cleanEntryName == fullNcxPath || cleanEntryName.equals(fullNcxPath, ignoreCase = true)) -> {
                            val origNcxXml = sourceZip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val rebuiltNcx = rebuildNcxXml(
                                originalNcxXml = origNcxXml,
                                translatedBookTitle = translatedBookTitle,
                                chapterTitleMap = chapterTitleMap,
                                chaptersToExport = if (!selectedChapterIds.isNullOrEmpty()) chaptersToExport else null,
                                opfDir = opfDir,
                                tocDir = cleanEntryName.substringBeforeLast('/', "")
                            )
                            writeZipEntry(zos, cleanEntryName, rebuiltNcx.toByteArray(Charsets.UTF_8))
                            writtenEntries.add(cleanEntryName)
                        }

                        // 3. EPUB3 Nav Entry -> Rebuild Nav with translated titles
                        fullNavPath != null && (cleanEntryName == navEntryName || cleanEntryName == fullNavPath || cleanEntryName.equals(fullNavPath, ignoreCase = true)) -> {
                            val origNavXml = sourceZip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val rebuiltNav = rebuildNavXml(
                                originalNavXml = origNavXml,
                                chapterTitleMap = chapterTitleMap,
                                chaptersToExport = if (!selectedChapterIds.isNullOrEmpty()) chaptersToExport else null,
                                opfDir = opfDir,
                                tocDir = cleanEntryName.substringBeforeLast('/', ""),
                                translatedBookTitle = translatedBookTitle,
                                originalBookTitle = originalBookTitle
                            )
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
                                // Only a real translation replaces the heading (it is translated with the body too).
                                val translatedChapterTitle = translatedChapterTitles[chapterEntity.id].orEmpty()

                                val rebuiltXhtml = rebuildChapterXhtml(
                                    rawXhtml = rawChapterXhtml,
                                    translatedTitle = translatedChapterTitle,
                                    chapterChunks = chapterChunks
                                )
                                writeZipEntry(zos, cleanEntryName, rebuiltXhtml.toByteArray(Charsets.UTF_8))
                                writtenEntries.add(cleanEntryName)
                            }
                        }

                        // If it is an unselected chapter file, omit writing it to keep partial EPUB clean
                        !selectedChapterIds.isNullOrEmpty() && (allOriginalChapterPaths.contains(cleanEntryName) || isChapterEntry(cleanEntryName, allChaptersByHref)) -> {
                            // Skip unselected chapter file
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

            return chaptersToExport
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

    fun rebuildChapterXhtml(
        rawXhtml: String,
        translatedTitle: String,
        chapterChunks: List<TranslationChunkEntity>
    ): String {
        val doc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // 1. Body first (element texts must still be the source text to be matched), title last.
        // 2. Pair each body chunk's source paragraphs with its translated paragraphs.
        val bodyChunks = chapterChunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
        val groups = bodyChunks.map { chunk ->
            // Junk such as `[""]` counts as untranslated: the source stays in place instead.
            splitParagraphs(chunk.sourceText) to
                splitParagraphs(TranslationQuality.usable(chunk.translatedText, chunk.sourceText) ?: chunk.sourceText)
        }
        val sourceParagraphs = groups.flatMap { it.first }

        if (sourceParagraphs.isNotEmpty()) {
            // 3. Find the elements the paragraphs were taken from at import time and replace their text
            //    chunk by chunk, so a translation that merged or split paragraphs only affects its own chunk.
            val elements = matchImportedBlocks(doc, sourceParagraphs)
            if (elements != null) {
                var offset = 0
                for ((source, translated) in groups) {
                    applyTranslation(elements.subList(offset, offset + source.size), translated, doc)
                    offset += source.size
                }
            } else {
                // The chapter file doesn't match what was imported: fill body text blocks in order
                // (headings keep their own title translation, media blocks are left alone).
                val blocks = EpubParser.textBlockElements(doc).filter {
                    it.normalName() !in HEADING_TAGS && it.select("img, svg, image").isEmpty()
                }
                val translated = groups.flatMap { it.second }
                val hasBlocks = EpubParser.textBlockElements(doc).isNotEmpty()
                if (hasBlocks && isLineImport(doc, sourceParagraphs.map(::normalized))) {
                    // Imported line by line (text outside block tags next to headings or paragraphs):
                    // each translated line replaces its source line where it is.
                    applyLineTranslation(doc.body(), groups)
                } else if (blocks.isNotEmpty()) {
                    applyTranslation(blocks, translated, doc)
                } else {
                    // Text without block tags (<br>/<div> layout): drop the source text, keep images.
                    removeTextContent(doc.body())
                    translated.forEach { doc.body().appendElement("p").text(it) }
                }
            }
        }

        // 4. The dedicated chapter-title translation wins over the heading's body translation. Without
        //    one, the heading keeps its body translation (it used to be overwritten with the Chinese
        //    title) and the page title follows it.
        if (translatedTitle.isNotBlank()) {
            doc.title(translatedTitle)
            doc.select("h1, h2, h3, .chapter-title, .title").firstOrNull { it.text().isNotBlank() }?.text(translatedTitle)
        } else {
            val heading = doc.select("h1, h2, h3").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            if (heading != null && TranslationQuality.hanCount(heading) == 0 && TranslationQuality.hanCount(doc.title()) > 0) {
                doc.title(heading)
            }
        }

        return doc.html()
    }

    /** "Chapter N" for a chapter whose title wasn't translated (as the reader shows it). */
    private fun fallbackChapterTitle(chapter: ChapterEntity): String =
        if (TranslationQuality.hanCount(chapter.title) > 0) "Chapter ${chapter.chapterOrder + 1}" else chapter.title

    /** Decodes %XX escapes only ('+' stays a plus, unlike URLDecoder). */
    internal fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && text[i + 1].isHexDigitChar() && text[i + 2].isHexDigitChar()) {
                bytes.write(text.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                val count = Character.charCount(text.codePointAt(i))
                val encoded = text.substring(i, i + count).toByteArray(Charsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                i += count
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Key for comparing hrefs: decoded, no fragment, forward slashes, no leading slash. */
    internal fun hrefKey(href: String): String =
        percentDecode(href.substringBefore('#').substringBefore('?')).replace('\\', '/').trimStart('/')

    /**
     * Resolves paths to the archive's real entry names: exact, then URL-decoded, then ignoring case.
     * Built once per export (looking up case-insensitively used to scan every entry each time).
     */
    internal class ZipEntryIndex(zip: ZipFile) {
        private val names = mutableSetOf<String>()
        private val byLowerCase = mutableMapOf<String, String>()

        init {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').trimStart('/')
                names += name
                byLowerCase.putIfAbsent(name.lowercase(), name)
            }
        }

        fun find(path: String): String? {
            val clean = path.replace('\\', '/').trimStart('/')
            if (clean in names) return clean
            val decoded = percentDecode(clean)
            if (decoded in names) return decoded
            return byLowerCase[clean.lowercase()] ?: byLowerCase[decoded.lowercase()]
        }

        fun resolve(baseDir: String, href: String): String? = find(EpubParser.resolveZipPath(baseDir, href))
    }

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** "Contents" headings of Chinese tables of contents. */
    private val CONTENTS_HEADINGS = setOf("目录", "目錄", "目次")

    private fun splitParagraphs(text: String): List<String> =
        text.split(Regex("\n\n+")).map { it.trim() }.filter { it.isNotBlank() }

    private fun normalized(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /**
     * The element list whose texts are exactly the paragraphs stored at import: the current block
     * selection, or the one older versions used. Null when the chapter no longer matches.
     */
    internal fun matchImportedBlocks(doc: Document, sourceParagraphs: List<String>): List<org.jsoup.nodes.Element>? {
        val expected = sourceParagraphs.map(::normalized)
        val candidates = listOf(EpubParser.textBlockElements(doc), EpubParser.legacyBlockElements(doc))
        candidates.firstOrNull { list -> list.map { normalized(it.text()) } == expected }?.let { return it }
        // Imported line by line (text outside block tags): matching blocks by count would be wrong.
        if (isLineImport(doc, expected)) return null
        return candidates.firstOrNull { it.size == expected.size }
    }

    /** True when the stored paragraphs are the chapter's lines (see EpubParser.extractTranslatableParagraphs). */
    private fun isLineImport(doc: Document, expectedNormalized: List<String>): Boolean {
        val body = doc.body() ?: return false
        return EpubParser.textLines(body).map(::normalized) == expectedNormalized
    }

    /**
     * Puts translated lines where their source lines are (a line is the text between line breaks and
     * block edges, as EpubParser.textLines reads it), so headings, line breaks and pictures stay in
     * place. When Google merged or split a chunk's lines, its translation goes into its first line.
     */
    private fun applyLineTranslation(body: org.jsoup.nodes.Element, groups: List<Pair<List<String>, List<String>>>) {
        val lines = lineTextNodes(body)
        var index = 0
        for ((source, translated) in groups) {
            val chunkLines = lines.subList(index.coerceAtMost(lines.size), (index + source.size).coerceAtMost(lines.size))
            index += source.size
            if (chunkLines.isEmpty()) continue
            if (translated.size == chunkLines.size) {
                chunkLines.forEachIndexed { i, nodes -> setLineText(nodes, translated[i]) }
            } else {
                setLineText(chunkLines.first(), translated.joinToString(" "))
                chunkLines.drop(1).forEach { setLineText(it, "") }
            }
        }
    }

    /** The text nodes of each non-blank line of [root], split exactly like EpubParser.textLines. */
    private fun lineTextNodes(root: org.jsoup.nodes.Element): List<List<org.jsoup.nodes.TextNode>> {
        val lines = mutableListOf<List<org.jsoup.nodes.TextNode>>()
        var current = mutableListOf<org.jsoup.nodes.TextNode>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotBlank()) lines += current
            current = mutableListOf()
            buffer.setLength(0)
        }
        org.jsoup.select.NodeTraversor.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                when (node) {
                    is org.jsoup.nodes.TextNode -> if (!EpubParser.isInNonText(node)) {
                        buffer.append(node.text())
                        current += node
                    }
                    is org.jsoup.nodes.Element -> if (node.normalName() == "br") flush()
                }
            }

            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is org.jsoup.nodes.Element && node.isBlock) flush()
            }
        }, root)
        flush()
        return lines
    }

    private fun setLineText(nodes: List<org.jsoup.nodes.TextNode>, text: String) {
        // The translation goes into the line's first text node with text, the rest are emptied.
        val target = nodes.firstOrNull { it.text().isNotBlank() } ?: nodes.first()
        nodes.forEach { it.text(if (it === target) text else "") }
    }

    /** Puts [translated] into [elements] in order; extra paragraphs become new <p> siblings, surplus elements are removed. */
    private fun applyTranslation(elements: List<org.jsoup.nodes.Element>, translated: List<String>, doc: Document) {
        if (elements.isEmpty()) {
            translated.forEach { doc.body().appendElement("p").text(it) }
            return
        }
        val common = minOf(elements.size, translated.size)
        for (i in 0 until common) elements[i].text(translated[i])
        if (translated.size > elements.size) {
            var anchor = elements.last()
            for (i in elements.size until translated.size) {
                val p = org.jsoup.nodes.Element("p").text(translated[i])
                anchor.after(p)
                anchor = p
            }
        } else {
            // Fewer translated paragraphs than source blocks: remove the rest so no source text leaks.
            for (i in translated.size until elements.size) elements[i].remove()
        }
    }

    private fun removeTextContent(root: org.jsoup.nodes.Element) {
        root.select("br").remove()
        val textNodes = mutableListOf<org.jsoup.nodes.TextNode>()
        org.jsoup.select.NodeTraversor.traverse({ node, _ ->
            if (node is org.jsoup.nodes.TextNode && node.text().isNotBlank()) textNodes += node
        }, root)
        textNodes.forEach { it.remove() }
    }

    private fun rebuildOpfXml(
        originalOpfXml: String,
        translatedTitle: String,
        translatedDesc: String,
        coverInfo: EpubParser.CoverInfo?,
        chaptersToExport: List<ChapterEntity>? = null,
        manifestMap: Map<String, ManifestItem>? = null,
        opfDir: String = ""
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

        // Filter Spine for partial / range exports if chaptersToExport is specified
        if (chaptersToExport != null && manifestMap != null) {
            val allowedHrefs = chaptersToExport.flatMap {
                listOf(
                    it.originalHref,
                    EpubParser.resolveZipPath(opfDir, it.originalHref),
                    try { URLDecoder.decode(it.originalHref, "UTF-8") } catch (_: Exception) { it.originalHref }
                )
            }.toSet()

            val spineElem = doc.select("spine, opf\\:spine").first()
            if (spineElem != null) {
                val itemrefs = spineElem.select("itemref, opf\\:itemref")
                for (itemref in itemrefs) {
                    val idref = itemref.attr("idref")
                    val item = manifestMap[idref]
                    val itemHref = item?.href ?: ""
                    val itemFullPath = EpubParser.resolveZipPath(opfDir, itemHref)

                    val isCoverOrTitlepage = (coverInfo != null && coverInfo.itemId == idref) ||
                            itemHref.contains("cover", ignoreCase = true) ||
                            idref.contains("cover", ignoreCase = true)

                    val isAllowedChapter = allowedHrefs.contains(itemHref) ||
                            allowedHrefs.contains(itemFullPath) ||
                            allowedHrefs.any { itemHref.endsWith(it) || itemFullPath.endsWith(it) }

                    if (!isCoverOrTitlepage && !isAllowedChapter) {
                        itemref.remove()
                    }
                }
            }
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

    /**
     * The title for a table-of-contents link: by the link's path resolved from the TOC file's folder
     * (decoded), then by the href as written, then the old suffix match.
     */
    private fun tocTitle(href: String, tocDir: String, chapterTitleMap: Map<String, String>): String? =
        chapterTitleMap[hrefKey(EpubParser.resolveZipPath(tocDir, href))]
            ?: chapterTitleMap[href]
            ?: chapterTitleMap[hrefKey(href)]
            ?: chapterTitleMap.entries.firstOrNull { href.endsWith(it.key) || it.key.endsWith(href) }?.value

    private fun rebuildNcxXml(
        originalNcxXml: String,
        translatedBookTitle: String,
        chapterTitleMap: Map<String, String>,
        chaptersToExport: List<ChapterEntity>? = null,
        opfDir: String = "",
        tocDir: String = opfDir
    ): String {
        val doc = Jsoup.parse(originalNcxXml, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // Update docTitle
        doc.select("docTitle > text").first()?.text(translatedBookTitle)

        val allowedHrefs = chaptersToExport?.flatMap {
            listOf(
                it.originalHref,
                EpubParser.resolveZipPath(opfDir, it.originalHref),
                try { URLDecoder.decode(it.originalHref, "UTF-8") } catch (_: Exception) { it.originalHref }
            )
        }?.toSet()

        // Update & filter navPoints
        for (navPoint in doc.select("navPoint")) {
            val contentSrc = navPoint.select("content").attr("src").substringBefore('#')
            val translated = tocTitle(contentSrc, tocDir, chapterTitleMap)

            if (allowedHrefs != null) {
                val isAllowed = allowedHrefs.contains(contentSrc) ||
                        allowedHrefs.contains(EpubParser.resolveZipPath(opfDir, contentSrc)) ||
                        allowedHrefs.any { contentSrc.endsWith(it) || it.endsWith(contentSrc) }
                if (!isAllowed) {
                    navPoint.remove()
                    continue
                }
            }

            if (translated != null) {
                navPoint.select("navLabel > text").first()?.text(translated)
            }
        }

        return doc.html()
    }

    private fun rebuildNavXml(
        originalNavXml: String,
        chapterTitleMap: Map<String, String>,
        chaptersToExport: List<ChapterEntity>? = null,
        opfDir: String = "",
        tocDir: String = opfDir,
        translatedBookTitle: String = "",
        originalBookTitle: String = ""
    ): String {
        val doc = Jsoup.parse(originalNavXml, "", Parser.htmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset(Charsets.UTF_8)

        // The page's own title and headings (not the links): the book title and "目录" were left in Chinese.
        if (translatedBookTitle.isNotBlank()) {
            fun englishFor(text: String): String? = when {
                TranslationQuality.hanCount(text) == 0 -> null
                normalized(text) == normalized(originalBookTitle) -> translatedBookTitle
                normalized(text).replace(" ", "") in CONTENTS_HEADINGS -> "Contents"
                else -> null
            }
            englishFor(doc.title())?.let { doc.title(it) }
            doc.select("h1, h2, h3, h4, h5, h6").filter { it.select("a").isEmpty() }.forEach { heading ->
                englishFor(heading.text())?.let { heading.text(it) }
            }
        }

        val allowedHrefs = chaptersToExport?.flatMap {
            listOf(
                it.originalHref,
                EpubParser.resolveZipPath(opfDir, it.originalHref),
                try { URLDecoder.decode(it.originalHref, "UTF-8") } catch (_: Exception) { it.originalHref }
            )
        }?.toSet()

        for (a in doc.select("nav a[href]")) {
            val href = a.attr("href").substringBefore('#')
            val translated = tocTitle(href, tocDir, chapterTitleMap)

            if (allowedHrefs != null) {
                val isAllowed = allowedHrefs.contains(href) ||
                        allowedHrefs.contains(EpubParser.resolveZipPath(opfDir, href)) ||
                        allowedHrefs.any { href.endsWith(it) || it.endsWith(href) }
                if (!isAllowed) {
                    a.parent()?.remove() ?: a.remove()
                    continue
                }
            }

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
