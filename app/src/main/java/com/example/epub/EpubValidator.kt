package com.example.epub

import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

object EpubValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    fun validatePreExport(
        originalEpub: ParsedEpub,
        savedChapters: List<ChapterEntity>,
        allChunks: List<TranslationChunkEntity>
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

        // 3. Check chunks integrity: no chunk missing, no duplicate chunk
        val chunkIds = mutableSetOf<String>()
        val chunkOrderMap = mutableMapOf<String, MutableSet<Int>>() // chapterId -> chunkOrders

        for (chunk in allChunks) {
            if (chunkIds.contains(chunk.id)) {
                errors.add("Duplicate chunk ID detected: ${chunk.id}")
            }
            chunkIds.add(chunk.id)

            val orders = chunkOrderMap.getOrPut(chunk.chapterId) { mutableSetOf() }
            if (orders.contains(chunk.chunkOrder)) {
                errors.add("Duplicate chunk order ${chunk.chunkOrder} for chapter ${chunk.chapterId}")
            }
            orders.add(chunk.chunkOrder)

            if (chunk.status != "COMPLETED" || chunk.translatedText.isNullOrBlank()) {
                errors.add("Incomplete chunk found: id=${chunk.id}, status=${chunk.status}, chapterOrder=${chunk.chapterOrder}")
            }
        }

        // 4. Verify all manifest resources still exist in original zip
        try {
            val zip = ZipFile(originalEpub.originalFile)
            zip.use { z ->
                for (item in originalEpub.manifest.values) {
                    val fullPath = if (originalEpub.opfDirectory.isBlank()) item.href else "${originalEpub.opfDirectory}/${item.href}"
                    val entry = z.getEntry(fullPath)
                    if (entry == null) {
                        errors.add("Manifest resource missing from archive: $fullPath")
                    }
                }

                // Check OPF entry
                val opfEntry = z.getEntry(originalEpub.opfPath)
                if (opfEntry == null) {
                    errors.add("OPF file missing: ${originalEpub.opfPath}")
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to inspect EPUB zip structure: ${e.message}")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }

    fun validateFinalEpub(exportedFile: File): ValidationResult {
        val errors = mutableListOf<String>()
        if (!exportedFile.exists() || exportedFile.length() == 0L) {
            errors.add("Exported EPUB file does not exist or is empty")
            return ValidationResult(false, errors)
        }

        try {
            val zip = ZipFile(exportedFile)
            zip.use { z ->
                // Must contain mimetype first
                val mimeEntry = z.getEntry("mimetype")
                if (mimeEntry == null) {
                    errors.add("Missing required 'mimetype' entry in EPUB root")
                }
                // Must contain container.xml
                val containerEntry = z.getEntry("META-INF/container.xml")
                if (containerEntry == null) {
                    errors.add("Missing required 'META-INF/container.xml'")
                } else {
                    val xml = z.getInputStream(containerEntry).bufferedReader().use { it.readText() }
                    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                    val rootfile = doc.select("rootfiles > rootfile").first()
                    if (rootfile == null || rootfile.attr("full-path").isBlank()) {
                        errors.add("Invalid container.xml: missing rootfile full-path")
                    } else {
                        val opfPath = rootfile.attr("full-path")
                        val opfEntry = z.getEntry(opfPath)
                        if (opfEntry == null) {
                            errors.add("Referenced OPF file missing in exported EPUB: $opfPath")
                        } else {
                            // Validate OPF content, manifest items, and cover presence
                            val opfXml = z.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                            val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())
                            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""

                            // Check Cover Manifest Item and File Existence if defined
                            val coverItem = opfDoc.select("manifest > item[properties*=cover-image]").first()
                                ?: opfDoc.select("metadata > meta[name=cover]").first()?.let { meta ->
                                    val coverId = meta.attr("content")
                                    opfDoc.select("manifest > item#$coverId, manifest > item[id=$coverId]").first()
                                }

                            if (coverItem != null) {
                                val coverHref = coverItem.attr("href")
                                val mediaType = coverItem.attr("media-type")
                                val cleanHref = coverHref.replace('\\', '/')
                                val coverFullPath = if (opfDir.isBlank()) cleanHref else "$opfDir/$cleanHref"

                                if (mediaType.isBlank() || !mediaType.startsWith("image/")) {
                                    errors.add("Cover manifest media-type is invalid: '$mediaType'")
                                }

                                val coverZipEntry = z.getEntry(coverFullPath)
                                if (coverZipEntry == null) {
                                    errors.add("Cover image file referenced by OPF missing from archive: $coverFullPath")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Exported EPUB zip structure is corrupted: ${e.message}")
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }
}
