package com.example.epub

import java.io.File

data class EpubMetadata(
    val title: String,
    val author: String,
    val description: String,
    val language: String = "zh",
    val coverItemId: String? = null,
    val coverHref: String? = null,
    val coverFullPath: String? = null,
    val coverMediaType: String? = null,
    val coverProperties: String? = null,
    val legacyMetaCover: String? = null
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String? = null
)

data class SpineItem(
    val idref: String,
    val linear: Boolean = true,
    val href: String
)

data class EpubQuickInfo(
    val metadata: EpubMetadata,
    val spine: List<SpineItem>,
    val manifest: Map<String, ManifestItem>,
    val opfPath: String,
    val opfDirectory: String,
    val coverBytes: ByteArray?,
    val coverMediaType: String?,
    val ncxHref: String?,
    val navHref: String?
)

data class ParsedChapterData(
    val chapterId: String,
    val chapterOrder: Int,
    val href: String,
    val title: String,
    val translatableParagraphs: List<String>
)

data class ParsedChapter(
    val chapterId: String,
    val chapterOrder: Int,
    val href: String,
    val fullPathInZip: String,
    val title: String,
    val rawXhtml: String,
    val translatableParagraphs: List<String>
)

data class ParsedEpub(
    val originalFile: File,
    val opfPath: String,
    val opfDirectory: String, // e.g. "OEBPS" or ""
    val metadata: EpubMetadata,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val ncxHref: String?,
    val navHref: String?,
    val coverBytes: ByteArray?,
    val coverMediaType: String?,
    val chapters: List<ParsedChapter>
)

data class ChunkDefinition(
    val chapterId: String,
    val chapterOrder: Int,
    val chunkId: String,
    val chunkOrder: Int,
    val chunkType: String, // TITLE, DESCRIPTION, CHAPTER_TITLE, CHAPTER_BODY
    val text: String,
    val paragraphStartIdx: Int = -1,
    val paragraphEndIdx: Int = -1
)
