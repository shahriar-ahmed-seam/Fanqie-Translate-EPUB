package com.example.epub

import com.example.data.db.ChapterEntity
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

object EpubValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    fun validatePreExport(
        originalEpub: ParsedEpub,
        savedChapters: List<ChapterEntity>,
        incompleteChunkCount: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // 1. Check chapter count
        if (savedChapters.size != originalEpub.chapters.size) {
            errors.add("Chapter count mismatch: expected ${originalEpub.chapters.size}, found ${savedChapters.size}")
        }

        // 2. Check chapter order
        savedChapters.forEachIndexed { index, chapter ->
            if (chapter.chapterOrder != index) {
                errors.add("Chapter order corrupted at index $index: chapter has order ${chapter.chapterOrder}")
            }
            if (index < originalEpub.chapters.size) {
                val orig = originalEpub.chapters[index]
                if (orig.href != chapter.originalHref) {
                    errors.add("Chapter spine href mismatch at $index: expected ${orig.href}, found ${chapter.originalHref}")
                }
            }
        }

        // 3. Check for incomplete chunks
        if (incompleteChunkCount > 0) {
            errors.add("$incompleteChunkCount translation chunks are incomplete or missing translation")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    fun validateFinalEpub(
        exportedFile: File,
        expectedChapterCount: Int? = null,
        hadOriginalCover: Boolean = false
    ): ValidationResult {
        val errors = mutableListOf<String>()
        if (!exportedFile.exists() || exportedFile.length() == 0L) {
            errors.add("Exported EPUB file does not exist or is empty")
            return ValidationResult(false, errors)
        }

        try {
            val zip = ZipFile(exportedFile)
            zip.use { z ->
                // 1. Must contain mimetype uncompressed and matching exact content
                val mimeEntry = z.getEntry("mimetype")
                if (mimeEntry == null) {
                    errors.add("Missing required 'mimetype' entry in EPUB root")
                } else {
                    val mimeContent = z.getInputStream(mimeEntry).bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
                    if (mimeContent != "application/epub+zip") {
                        errors.add("Invalid 'mimetype' entry content: expected 'application/epub+zip', found '$mimeContent'")
                    }
                }

                // 2. Must contain META-INF/container.xml
                val containerEntry = z.getEntry("META-INF/container.xml")
                if (containerEntry == null) {
                    errors.add("Missing required 'META-INF/container.xml'")
                    return ValidationResult(false, errors)
                }

                val containerXml = z.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
                val rootfile = containerDoc.select("rootfiles > rootfile").first()
                if (rootfile == null || rootfile.attr("full-path").isBlank()) {
                    errors.add("Invalid container.xml: missing rootfile full-path")
                    return ValidationResult(false, errors)
                }

                val opfPath = rootfile.attr("full-path").replace('\\', '/')
                val opfEntry = z.getEntry(opfPath)
                if (opfEntry == null) {
                    errors.add("Referenced OPF file missing in exported EPUB: $opfPath")
                    return ValidationResult(false, errors)
                }

                val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""

                // 3. Parse and Validate OPF
                val opfXml = z.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())

                // Check title & language
                val title = opfDoc.select("metadata > dc\\:title, metadata > title").text()
                if (title.isBlank()) {
                    errors.add("Exported OPF metadata is missing dc:title")
                }

                // Build manifest mapping (id -> href, mediaType, properties)
                val manifestMap = mutableMapOf<String, Triple<String, String, String>>() // id -> (href, mediaType, props)
                val manifestElements = opfDoc.select("manifest > item, opf\\:manifest > opf\\:item, item")
                for (item in manifestElements) {
                    val id = item.attr("id")
                    val href = item.attr("href")
                    val mediaType = item.attr("media-type")
                    val props = item.attr("properties")
                    if (id.isNotBlank() && href.isNotBlank()) {
                        manifestMap[id] = Triple(href, mediaType, props)
                    }
                }

                if (manifestMap.isEmpty()) {
                    errors.add("Exported OPF contains an empty manifest")
                }

                // 4. Validate Spine and Spine Chapters
                val spineItems = opfDoc.select("spine > itemref, opf\\:spine > opf\\:itemref, itemref")
                if (spineItems.isEmpty()) {
                    errors.add("Exported OPF contains an empty spine")
                }

                if (expectedChapterCount != null && spineItems.size != expectedChapterCount) {
                    errors.add("Exported spine chapter count mismatch: expected $expectedChapterCount, found ${spineItems.size}")
                }

                val seenChapterPaths = mutableSetOf<String>()
                spineItems.forEachIndexed { index, itemref ->
                    val idref = itemref.attr("idref")
                    val item = manifestMap[idref]
                    if (item == null) {
                        errors.add("Spine itemref at index $index refers to missing manifest id: '$idref'")
                    } else {
                        val href = item.first
                        val chapterFullPath = resolveZipPath(opfDir, href)
                        if (seenChapterPaths.contains(chapterFullPath)) {
                            errors.add("Duplicate spine chapter path detected: '$chapterFullPath'")
                        }
                        seenChapterPaths.add(chapterFullPath)

                        val chapterZipEntry = findZipEntry(z, chapterFullPath)
                        if (chapterZipEntry == null) {
                            errors.add("Spine chapter file missing from archive: '$chapterFullPath'")
                        } else {
                            // Verify that chapter XHTML is valid and non-empty
                            try {
                                val xhtml = z.getInputStream(chapterZipEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                                if (xhtml.isBlank()) {
                                    errors.add("Spine chapter file is empty: '$chapterFullPath'")
                                }
                            } catch (e: Exception) {
                                errors.add("Failed to read chapter file '$chapterFullPath': ${e.message}")
                            }
                        }
                    }
                }

                // 5. Validate Navigation if declared
                val spineToc = opfDoc.select("spine, opf\\:spine").first()?.attr("toc")
                if (!spineToc.isNullOrBlank()) {
                    val ncxItem = manifestMap[spineToc]
                    if (ncxItem != null) {
                        val ncxFullPath = resolveZipPath(opfDir, ncxItem.first)
                        if (findZipEntry(z, ncxFullPath) == null) {
                            errors.add("NCX TOC file declared in spine missing from archive: '$ncxFullPath'")
                        }
                    }
                }

                val navItem = manifestMap.values.firstOrNull { it.third.contains("nav", ignoreCase = true) }
                if (navItem != null) {
                    val navFullPath = resolveZipPath(opfDir, navItem.first)
                    if (findZipEntry(z, navFullPath) == null) {
                        errors.add("EPUB3 Nav file missing from archive: '$navFullPath'")
                    }
                }

                // 6. Validate Cover if declared or expected
                val manifestItems = manifestMap.mapValues { ManifestItem(it.key, it.value.first, it.value.second, it.value.third) }
                val coverInfo = EpubParser.findCoverInfo(opfDoc, manifestItems, opfDir, z)

                if (coverInfo != null) {
                    // 1. Find cover manifest item
                    val coverItem = manifestMap[coverInfo.itemId]
                    if (coverItem == null) {
                        errors.add("Cover item ID '${coverInfo.itemId}' referenced in OPF not found in manifest")
                    } else {
                        // 2. Verify media type is image
                        val mediaType = coverItem.second
                        val isImageMime = mediaType.startsWith("image/", ignoreCase = true) ||
                                mediaType in listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml")
                        if (!isImageMime) {
                            errors.add("Cover manifest item '${coverInfo.itemId}' has invalid non-image media-type '$mediaType'")
                        }

                        // 3. Resolve href relative to OPF directory & verify file exists in output zip
                        val coverFullPath = EpubParser.resolveZipPath(opfDir, coverItem.first)
                        val coverEntry = EpubParser.findZipEntry(z, coverFullPath)
                        if (coverEntry == null) {
                            errors.add("Cover image file referenced by OPF missing from archive: '$coverFullPath'")
                        } else {
                            try {
                                val bytes = z.getInputStream(coverEntry).use { it.readBytes() }
                                if (bytes.isEmpty()) {
                                    errors.add("Cover image file in archive is empty (0 bytes): '$coverFullPath'")
                                }
                            } catch (e: Exception) {
                                errors.add("Failed to read cover image file '$coverFullPath': ${e.message}")
                            }
                        }

                        // 4. Verify OPF cover relationship
                        val hasEp3CoverProp = coverItem.third.contains("cover-image", ignoreCase = true)
                        val metaCoverContent = opfDoc.select("metadata > meta[name=cover], opf\\:metadata > opf\\:meta[name=cover], meta[name=cover]").first()?.attr("content")
                        val hasEp2CoverMeta = metaCoverContent == coverInfo.itemId

                        if (!hasEp3CoverProp && !hasEp2CoverMeta) {
                            errors.add("Exported OPF does not have a valid cover declaration (missing properties='cover-image' and <meta name='cover'> for '${coverInfo.itemId}')")
                        }
                    }
                } else if (hadOriginalCover) {
                    errors.add("Original EPUB had a cover image, but the exported EPUB does not have a valid cover declaration and image file")
                }
            }
        } catch (e: Exception) {
            errors.add("Exported EPUB zip structure validation exception: ${e.message}")
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }

    private fun findZipEntry(zip: ZipFile, path: String): java.util.zip.ZipEntry? = EpubParser.findZipEntry(zip, path)

    private fun resolveZipPath(baseDir: String, href: String): String = EpubParser.resolveZipPath(baseDir, href)
}
