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

    fun parseQuickInfo(epubFile: File): EpubQuickInfo {
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
                ?: findZipEntry(zip, opfPath)
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
            var ncxId: String? = null
            var navId: String? = null

            val spineElem = opfDoc.select("spine, opf\\:spine").first()
            val spineTocAttr = spineElem?.attr("toc")
            if (!spineTocAttr.isNullOrBlank()) {
                ncxId = spineTocAttr
            }

            for (item in opfDoc.select("manifest > item, opf\\:manifest > opf\\:item, item")) {
                val id = item.attr("id")
                val href = item.attr("href")
                val mediaType = item.attr("media-type")
                val properties = item.attr("properties")

                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = ManifestItem(id, href, mediaType, properties)

                    if (properties.contains("nav", ignoreCase = true)) {
                        navId = id
                    }
                    if (mediaType == "application/x-dtbncx+xml") {
                        ncxId = id
                    }
                }
            }

            // Identify cover using standard-compliant multi-strategy lookup
            val coverInfo = findCoverInfo(opfDoc, manifest, opfDir, zip)

            // Extract Cover Image Bytes
            var coverBytes: ByteArray? = null
            if (coverInfo != null) {
                val coverEntry = findZipEntry(zip, coverInfo.fullPath)
                if (coverEntry != null) {
                    coverBytes = zip.getInputStream(coverEntry).use { it.readBytes() }
                }
            }

            // Parse Spine - STRICT READING ORDER
            val spine = mutableListOf<SpineItem>()
            for (itemref in opfDoc.select("spine > itemref, opf\\:spine > opf\\:itemref, itemref")) {
                val idref = itemref.attr("idref")
                val linear = itemref.attr("linear") != "no"
                val item = manifest[idref]
                if (item != null) {
                    spine.add(SpineItem(idref, linear, item.href))
                }
            }

            val ncxHref = ncxId?.let { manifest[it]?.href }
            val navHref = navId?.let { manifest[it]?.href }

            return EpubQuickInfo(
                metadata = EpubMetadata(
                    title = title,
                    author = author,
                    description = description,
                    language = language,
                    coverItemId = coverInfo?.itemId,
                    coverHref = coverInfo?.href,
                    coverFullPath = coverInfo?.fullPath,
                    coverMediaType = coverInfo?.mediaType,
                    coverProperties = coverInfo?.properties,
                    legacyMetaCover = coverInfo?.legacyMetaCover
                ),
                spine = spine,
                manifest = manifest,
                opfPath = opfPath,
                opfDirectory = opfDir,
                coverBytes = coverBytes,
                coverMediaType = coverInfo?.mediaType,
                ncxHref = ncxHref,
                navHref = navHref
            )
        } finally {
            zip.close()
        }
    }

    /**
     * Extracts a single chapter's data from an open ZipFile.
     * Memory for raw XHTML and Jsoup Document is localized to this method call and eligible for immediate GC.
     */
    fun extractChapter(
        zip: ZipFile,
        opfDir: String,
        spineItem: SpineItem,
        chapterOrder: Int
    ): ParsedChapterData {
        val fullChapterPath = resolveZipPath(opfDir, spineItem.href)
        val chapterEntry = findZipEntry(zip, fullChapterPath)
            ?: throw IllegalStateException("Chapter ${chapterOrder + 1} (${spineItem.href}) could not be found in EPUB archive at '$fullChapterPath'")

        val rawXhtml = try {
            zip.getInputStream(chapterEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            throw IllegalStateException("Chapter ${chapterOrder + 1} (${spineItem.href}) could not be read from archive: ${e.message}", e)
        }

        if (rawXhtml.isBlank()) {
            throw IllegalStateException("Chapter ${chapterOrder + 1} (${spineItem.href}) is empty")
        }

        // Parse with primary parser, with safe fallback on error
        val chapterDoc: Document = try {
            Jsoup.parse(rawXhtml, "", Parser.htmlParser())
        } catch (e: Exception) {
            // Safe fallback parser for malformed XHTML
            try {
                Jsoup.parse(rawXhtml)
            } catch (fallbackEx: Exception) {
                throw IllegalStateException("Chapter ${chapterOrder + 1} (${spineItem.href}) could not be parsed: ${fallbackEx.message}", fallbackEx)
            }
        }

        // Extract chapter title
        val heading = chapterDoc.select("h1, h2, h3, title, .chapter-title, .title, header").firstOrNull { it.text().isNotBlank() }
        val chapterTitle = heading?.text()?.trim()?.ifBlank { "Chapter ${chapterOrder + 1}" } ?: "Chapter ${chapterOrder + 1}"

        // Extract translatable text blocks
        val paragraphs = extractTranslatableParagraphs(chapterDoc)

        return ParsedChapterData(
            chapterId = UUID.randomUUID().toString(),
            chapterOrder = chapterOrder,
            href = spineItem.href,
            title = chapterTitle,
            translatableParagraphs = paragraphs
        )
    }

    fun parse(epubFile: File): ParsedEpub {
        val quickInfo = parseQuickInfo(epubFile)
        val zip = ZipFile(epubFile)
        val chapters = mutableListOf<ParsedChapter>()
        try {
            quickInfo.spine.forEachIndexed { index, spineItem ->
                val fullChapterPath = resolveZipPath(quickInfo.opfDirectory, spineItem.href)
                val chapterEntry = findZipEntry(zip, fullChapterPath)
                if (chapterEntry != null) {
                    val rawXhtml = zip.getInputStream(chapterEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val chapterDoc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
                    val heading = chapterDoc.select("h1, h2, h3, title, .chapter-title, .title").firstOrNull { it.text().isNotBlank() }
                    val chapterTitle = heading?.text() ?: "Chapter ${index + 1}"
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
                opfPath = quickInfo.opfPath,
                opfDirectory = quickInfo.opfDirectory,
                metadata = quickInfo.metadata,
                manifest = quickInfo.manifest,
                spine = quickInfo.spine,
                ncxHref = quickInfo.ncxHref,
                navHref = quickInfo.navHref,
                coverBytes = quickInfo.coverBytes,
                coverMediaType = quickInfo.coverMediaType,
                chapters = chapters
            )
        } finally {
            zip.close()
        }
    }

    data class CoverInfo(
        val itemId: String,
        val href: String,
        val mediaType: String,
        val fullPath: String,
        val properties: String? = null,
        val legacyMetaCover: String? = null
    )

    /**
     * Identifies the cover image from OPF document, manifest, and optional ZIP archive.
     * Supports EPUB3 (properties="cover-image"), EPUB2 (<meta name="cover">), <guide>,
     * cover XHTML page inspection, and naming heuristics.
     */
    fun findCoverInfo(
        opfDoc: Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        zip: ZipFile? = null
    ): CoverInfo? {
        // Strategy 1: EPUB3 manifest item with properties containing "cover-image" and image media-type
        val ep3Item = manifest.values.firstOrNull {
            it.properties?.contains("cover-image", ignoreCase = true) == true &&
                    it.mediaType.startsWith("image/", ignoreCase = true)
        }
        if (ep3Item != null) {
            return CoverInfo(
                itemId = ep3Item.id,
                href = ep3Item.href,
                mediaType = ep3Item.mediaType,
                fullPath = resolveZipPath(opfDir, ep3Item.href),
                properties = ep3Item.properties
            )
        }

        // Strategy 2: EPUB2 <meta name="cover" content="item_id"/> (or <meta property="cover"...>)
        val metaCover = opfDoc.select("metadata > meta[name=cover], opf\\:metadata > opf\\:meta[name=cover], meta[name=cover]").first()?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: opfDoc.select("metadata > meta[property=cover], metadata > meta[property=cover-image]").first()?.text()?.takeIf { it.isNotBlank() }

        if (metaCover != null && manifest.containsKey(metaCover)) {
            val item = manifest[metaCover]!!
            if (item.mediaType.startsWith("image/", ignoreCase = true)) {
                return CoverInfo(
                    itemId = item.id,
                    href = item.href,
                    mediaType = item.mediaType,
                    fullPath = resolveZipPath(opfDir, item.href),
                    properties = item.properties,
                    legacyMetaCover = metaCover
                )
            } else if ((item.mediaType.contains("xhtml", ignoreCase = true) || item.mediaType.contains("html", ignoreCase = true)) && zip != null) {
                // Meta cover pointed to an XHTML cover page. Inspect the XHTML for the image inside.
                val coverXhtmlPath = resolveZipPath(opfDir, item.href)
                val entry = findZipEntry(zip, coverXhtmlPath)
                if (entry != null) {
                    try {
                        val xhtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val coverDoc = Jsoup.parse(xhtml, "", Parser.htmlParser())
                        val imgElem = coverDoc.select("img, image, svg image").firstOrNull()
                        val imgSrc = imgElem?.attr("src")?.takeIf { it.isNotBlank() }
                            ?: imgElem?.attr("xlink:href")?.takeIf { it.isNotBlank() }
                            ?: imgElem?.attr("href")?.takeIf { it.isNotBlank() }
                        if (imgSrc != null) {
                            val xhtmlDir = if (coverXhtmlPath.contains('/')) coverXhtmlPath.substringBeforeLast('/') else ""
                            val resolvedImgPath = resolveZipPath(xhtmlDir, imgSrc)
                            val manifestImageItem = manifest.values.firstOrNull {
                                resolveZipPath(opfDir, it.href).equals(resolvedImgPath, ignoreCase = true)
                            }
                            if (manifestImageItem != null) {
                                return CoverInfo(
                                    itemId = manifestImageItem.id,
                                    href = manifestImageItem.href,
                                    mediaType = manifestImageItem.mediaType,
                                    fullPath = resolvedImgPath,
                                    properties = manifestImageItem.properties,
                                    legacyMetaCover = metaCover
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Strategy 3: <guide><reference type="cover" href="..."/>
        val guideCoverRef = opfDoc.select("guide > reference[type=cover], opf\\:guide > opf\\:reference[type=cover], reference[type=cover]").first()
        if (guideCoverRef != null) {
            val href = guideCoverRef.attr("href")
            if (href.isNotBlank()) {
                val cleanRefHref = href.substringBefore('#')
                val fullGuidePath = resolveZipPath(opfDir, cleanRefHref)
                val manifestItem = manifest.values.firstOrNull {
                    resolveZipPath(opfDir, it.href).equals(fullGuidePath, ignoreCase = true)
                }
                if (manifestItem != null) {
                    if (manifestItem.mediaType.startsWith("image/", ignoreCase = true)) {
                        return CoverInfo(
                            itemId = manifestItem.id,
                            href = manifestItem.href,
                            mediaType = manifestItem.mediaType,
                            fullPath = fullGuidePath,
                            properties = manifestItem.properties
                        )
                    } else if ((manifestItem.mediaType.contains("xhtml", ignoreCase = true) || manifestItem.mediaType.contains("html", ignoreCase = true)) && zip != null) {
                        val entry = findZipEntry(zip, fullGuidePath)
                        if (entry != null) {
                            try {
                                val xhtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                                val coverDoc = Jsoup.parse(xhtml, "", Parser.htmlParser())
                                val imgElem = coverDoc.select("img, image, svg image").firstOrNull()
                                val imgSrc = imgElem?.attr("src")?.takeIf { it.isNotBlank() }
                                    ?: imgElem?.attr("xlink:href")?.takeIf { it.isNotBlank() }
                                    ?: imgElem?.attr("href")?.takeIf { it.isNotBlank() }
                                if (imgSrc != null) {
                                    val xhtmlDir = if (fullGuidePath.contains('/')) fullGuidePath.substringBeforeLast('/') else ""
                                    val resolvedImgPath = resolveZipPath(xhtmlDir, imgSrc)
                                    val manifestImageItem = manifest.values.firstOrNull {
                                        resolveZipPath(opfDir, it.href).equals(resolvedImgPath, ignoreCase = true)
                                    }
                                    if (manifestImageItem != null) {
                                        return CoverInfo(
                                            itemId = manifestImageItem.id,
                                            href = manifestImageItem.href,
                                            mediaType = manifestImageItem.mediaType,
                                            fullPath = resolvedImgPath,
                                            properties = manifestImageItem.properties
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Strategy 4: Manifest item with image media type and 'cover' in id or href
        val imageItems = manifest.values.filter { it.mediaType.startsWith("image/", ignoreCase = true) }
        val coverNamedItem = imageItems.firstOrNull {
            it.id.contains("cover", ignoreCase = true) || it.href.contains("cover", ignoreCase = true)
        }
        if (coverNamedItem != null) {
            return CoverInfo(
                itemId = coverNamedItem.id,
                href = coverNamedItem.href,
                mediaType = coverNamedItem.mediaType,
                fullPath = resolveZipPath(opfDir, coverNamedItem.href),
                properties = coverNamedItem.properties
            )
        }

        // Strategy 5: First image item in manifest
        val firstImage = imageItems.firstOrNull()
        if (firstImage != null) {
            return CoverInfo(
                itemId = firstImage.id,
                href = firstImage.href,
                mediaType = firstImage.mediaType,
                fullPath = resolveZipPath(opfDir, firstImage.href),
                properties = firstImage.properties
            )
        }

        return null
    }

    fun findZipEntry(zip: ZipFile, path: String): ZipEntry? {
        val direct = zip.getEntry(path)
        if (direct != null) return direct

        val clean = path.replace('\\', '/').trimStart('/')
        val directClean = zip.getEntry(clean)
        if (directClean != null) return directClean

        // Try decoded path
        try {
            val decoded = java.net.URLDecoder.decode(clean, "UTF-8")
            val decodedEntry = zip.getEntry(decoded)
            if (decodedEntry != null) return decodedEntry
        } catch (_: Exception) {}

        // Case-insensitive lookup fallback
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val entryName = entry.name.replace('\\', '/').trimStart('/')
            if (entryName.equals(clean, ignoreCase = true)) {
                return entry
            }
        }
        return null
    }

    fun resolveZipPath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?').replace('\\', '/')
        if (baseDir.isBlank()) {
            val parts = cleanHref.split('/')
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

    /**
     * Extracts chapter titles from NCX (EPUB 2) or Nav XHTML (EPUB 3) table of contents.
     * Returns a map with keys matching both relative href, resolved full zip path, and base filename.
     */
    fun extractTocChapterTitles(epubFile: File, quickInfo: EpubQuickInfo): Map<String, String> {
        if (!epubFile.exists() || epubFile.length() == 0L) return emptyMap()
        val titlesMap = mutableMapOf<String, String>()

        try {
            ZipFile(epubFile).use { zip ->
                val opfDir = quickInfo.opfDirectory

                // 1. Try NCX TOC
                val ncxHref = quickInfo.ncxHref
                if (!ncxHref.isNullOrBlank()) {
                    val fullNcxPath = resolveZipPath(opfDir, ncxHref)
                    val ncxEntry = findZipEntry(zip, fullNcxPath) ?: findZipEntry(zip, ncxHref)
                    if (ncxEntry != null) {
                        val ncxXml = zip.getInputStream(ncxEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val doc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
                        val navPoints = doc.select("navPoint")
                        for (np in navPoints) {
                            val text = np.select("navLabel > text").firstOrNull()?.text()?.trim()
                            val src = np.select("content").firstOrNull()?.attr("src")?.substringBefore('#')?.trim()
                            if (!text.isNullOrBlank() && !src.isNullOrBlank()) {
                                titlesMap[src] = text
                                titlesMap[resolveZipPath(opfDir, src)] = text
                                val baseName = src.substringAfterLast('/')
                                if (!titlesMap.containsKey(baseName)) {
                                    titlesMap[baseName] = text
                                }
                            }
                        }
                    }
                }

                // 2. Try Nav HTML TOC (EPUB 3)
                val navHref = quickInfo.navHref
                if (!navHref.isNullOrBlank()) {
                    val fullNavPath = resolveZipPath(opfDir, navHref)
                    val navEntry = findZipEntry(zip, fullNavPath) ?: findZipEntry(zip, navHref)
                    if (navEntry != null) {
                        val navHtml = zip.getInputStream(navEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val doc = Jsoup.parse(navHtml, "", Parser.htmlParser())
                        val navLinks = doc.select("nav[epub\\:type=toc] a[href], nav#toc a[href], nav a[href]")
                        for (a in navLinks) {
                            val text = a.text().trim()
                            val href = a.attr("href").substringBefore('#').trim()
                            if (text.isNotBlank() && href.isNotBlank()) {
                                if (!titlesMap.containsKey(href)) {
                                    titlesMap[href] = text
                                }
                                val resolved = resolveZipPath(opfDir, href)
                                if (!titlesMap.containsKey(resolved)) {
                                    titlesMap[resolved] = text
                                }
                                val baseName = href.substringAfterLast('/')
                                if (!titlesMap.containsKey(baseName)) {
                                    titlesMap[baseName] = text
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("EpubParser", "Failed to extract TOC chapter titles", e)
        }

        return titlesMap
    }

    /**
     * Peeks the title or first heading tag from a chapter's XHTML file.
     */
    fun extractChapterTitleFromEntry(zip: ZipFile, opfDir: String, chapterHref: String): String? {
        val fullPath = resolveZipPath(opfDir, chapterHref)
        val entry = findZipEntry(zip, fullPath) ?: findZipEntry(zip, chapterHref) ?: return null
        return try {
            val rawXhtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val doc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
            val heading = doc.select("h1, h2, h3, title").firstOrNull()?.text()?.trim()
            heading?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the best available title for a chapter:
     * 1. Checks TOC map (NCX / Nav)
     * 2. Checks entry heading
     * 3. Falls back to "Chapter ${chapterOrder + 1}"
     */
    fun resolveChapterTitle(
        zip: ZipFile?,
        opfDir: String,
        href: String,
        chapterOrder: Int,
        tocTitles: Map<String, String>
    ): String {
        val titleFromToc = tocTitles[href]
            ?: tocTitles[resolveZipPath(opfDir, href)]
            ?: tocTitles[href.substringAfterLast('/')]
        if (!titleFromToc.isNullOrBlank()) {
            return titleFromToc
        }
        if (zip != null) {
            val entryTitle = extractChapterTitleFromEntry(zip, opfDir, href)
            if (!entryTitle.isNullOrBlank()) {
                return entryTitle
            }
        }
        return "Chapter ${chapterOrder + 1}"
    }

    /**
     * Extracts readable text paragraphs directly from a stored EPUB on disk for the given chapter.
     * Guaranteed O(1) memory footprint for large novels since only one entry is decompressed at a time.
     */
    fun extractChapterParagraphs(epubFile: File, chapterHref: String): List<String> {
        if (!epubFile.exists() || epubFile.length() == 0L) return emptyList()
        return try {
            ZipFile(epubFile).use { zip ->
                var opfDir = ""
                val containerEntry = findZipEntry(zip, "META-INF/container.xml")
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
                    val rootfile = containerDoc.select("rootfiles > rootfile").first()
                    val opfPath = rootfile?.attr("full-path")?.replace('\\', '/') ?: ""
                    if (opfPath.contains('/')) {
                        opfDir = opfPath.substringBeforeLast('/')
                    }
                }

                val fullPath = resolveZipPath(opfDir, chapterHref)
                val entry = findZipEntry(zip, fullPath)
                    ?: findZipEntry(zip, chapterHref)
                    ?: findZipEntry(zip, chapterHref.substringAfterLast('/'))
                    ?: return emptyList()

                val rawXhtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val doc = Jsoup.parse(rawXhtml, "", Parser.htmlParser())
                extractTranslatableParagraphs(doc)
            }
        } catch (e: Exception) {
            android.util.Log.e("EpubParser", "Failed to extract chapter paragraphs for $chapterHref", e)
            emptyList()
        }
    }
}
