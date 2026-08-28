package com.example.epub

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object EpubParser {

    fun copyUriToTempFile(context: Context, uri: Uri, tempFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")
    }

    fun parse(epubFile: File): ParsedEpub {
        val zip = ZipFile(epubFile)
        try {
            // 1. Locate container.xml
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found")

            val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
            val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val rootfileElement = containerDoc.select("rootfiles > rootfile").first()
                ?: throw IllegalArgumentException("Invalid container.xml: No rootfile defined")

            val opfPath = rootfileElement.attr("full-path").replace('\\', '/')
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""

            // 2. Parse OPF file
            val opfEntry = zip.getEntry(opfPath)
                ?: throw IllegalArgumentException("OPF file not found at: $opfPath")
            val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
            val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())

            // Parse Metadata
            val title = opfDoc.select("metadata > dc\\:title, metadata > title").text().ifBlank { "Untitled" }
            val author = opfDoc.select("metadata > dc\\:creator, metadata > creator").text().ifBlank { "Unknown Author" }
            val description = opfDoc.select("metadata > dc\\:description, metadata > description").text()
            val language = opfDoc.select("metadata > dc\\:language, metadata > language").text().ifBlank { "zh" }

            // Parse Manifest
            val manifest = mutableMapOf<String, ManifestItem>()
            var coverItemId: String? = null
            var ncxId: String? = null
            var navId: String? = null

            val spineElem = opfDoc.select("spine").first()
            val spineTocAttr = spineElem?.attr("toc")
            if (!spineTocAttr.isNullOrBlank()) {
                ncxId = spineTocAttr
            }

            // Cover identification strategy:
            // 1. EPUB3 properties="cover-image" on manifest item
            // 2. EPUB2 <meta name="cover" content="item_id"/> in metadata
            // 3. Fallback: manifest item with image media type and 'cover' in id or href
            for (item in opfDoc.select("manifest > item")) {
                val id = item.attr("id")
                val href = item.attr("href")
                val mediaType = item.attr("media-type")
                val properties = item.attr("properties")

                manifest[id] = ManifestItem(id, href, mediaType, properties)

                if (properties.contains("cover-image", ignoreCase = true)) {
                    coverItemId = id
                }
                if (properties.contains("nav", ignoreCase = true)) {
                    navId = id
                }
                if (mediaType == "application/x-dtbncx+xml") {
                    ncxId = id
                }
            }

            // Cover meta tag check e.g. <meta name="cover" content="cover-image"/>
            if (coverItemId == null) {
                val metaCover = opfDoc.select("metadata > meta[name=cover]").attr("content")
                if (metaCover.isNotBlank() && manifest.containsKey(metaCover)) {
                    coverItemId = metaCover
                }
            }

            // Fallback cover search
            if (coverItemId == null) {
                coverItemId = manifest.values.firstOrNull {
                    it.mediaType.startsWith("image/") && (it.id.contains("cover", ignoreCase = true) || it.href.contains("cover", ignoreCase = true))
                }?.id
            }

            // Extract Cover Image Bytes
            var coverBytes: ByteArray? = null
            var coverMediaType: String? = null
            var coverHref: String? = null
            var coverFullPath: String? = null

            if (coverItemId != null && manifest.containsKey(coverItemId)) {
                val coverItem = manifest[coverItemId]!!
                coverMediaType = coverItem.mediaType
                coverHref = coverItem.href
                coverFullPath = resolveZipPath(opfDir, coverItem.href)
                val coverEntry = zip.getEntry(coverFullPath)
                if (coverEntry != null) {
                    coverBytes = zip.getInputStream(coverEntry).use { it.readBytes() }
                }
            }

            // Parse Spine - STRICT READING ORDER
            val spine = mutableListOf<SpineItem>()
            for (itemref in opfDoc.select("spine > itemref")) {
                val idref = itemref.attr("idref")
                val linear = itemref.attr("linear") != "no"
                val item = manifest[idref]
                if (item != null) {
                    spine.add(SpineItem(idref, linear, item.href))
                }
            }

            val ncxHref = ncxId?.let { manifest[it]?.href }
            val navHref = navId?.let { manifest[it]?.href }

            // 3. Parse Chapters following exact spine order
            val chapters = mutableListOf<ParsedChapter>()
            spine.forEachIndexed { index, spineItem ->
                val fullChapterPath = resolveZipPath(opfDir, spineItem.href)
                val chapterEntry = zip.getEntry(fullChapterPath)
                if (chapterEntry != null) {
                    val rawXhtml = zip.getInputStream(chapterEntry).bufferedReader().use { it.readText() }
                    val chapterDoc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())

                    // Extract chapter title
                    val heading = chapterDoc.select("h1, h2, h3, title, .chapter-title, .title").firstOrNull { it.text().isNotBlank() }
                    val chapterTitle = heading?.text() ?: "Chapter ${index + 1}"

                    // Extract translatable text blocks
                    val paragraphs = extractTranslatableParagraphs(chapterDoc)

                    chapters.add(
                        ParsedChapter(
                            chapterId = UUID.randomUUID().toString(),
                            chapterOrder = index,
                            href = spineItem.href,
                            fullPathInZip = fullChapterPath,
                            title = chapterTitle,
                            rawXhtml = rawXhtml,
                            translatableParagraphs = paragraphs
                        )
                    )
                }
            }

            return ParsedEpub(
                originalFile = epubFile,
                opfPath = opfPath,
                opfDirectory = opfDir,
                metadata = EpubMetadata(
                    title = title,
                    author = author,
                    description = description,
                    language = language,
                    coverItemId = coverItemId,
                    coverHref = coverHref,
                    coverFullPath = coverFullPath
                ),
                manifest = manifest,
                spine = spine,
                ncxHref = ncxHref,
                navHref = navHref,
                coverBytes = coverBytes,
                coverMediaType = coverMediaType,
                chapters = chapters
            )
        } finally {
            zip.close()
        }
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

    /**
     * Extracts text blocks (paragraphs, headers, list items, blockquotes, divs with direct text)
     * while preserving document reading flow.
     */
    fun extractTranslatableParagraphs(doc: Document): List<String> {
        val elements = doc.select("p, h1, h2, h3, h4, h5, h6, blockquote, li, dt, dd")
        val result = mutableListOf<String>()

        if (elements.isNotEmpty()) {
            for (element in elements) {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(text)
                }
            }
        } else {
            // Fallback to body text split by newlines if standard tags are not present
            val bodyText = doc.body()?.text() ?: ""
            bodyText.split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                result.add(it)
            }
        }
        return result
    }
}
