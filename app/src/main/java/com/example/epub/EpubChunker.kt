package com.example.epub

import java.util.UUID

object EpubChunker {

    fun generateChunksForMetadata(
        title: String,
        description: String
    ): List<ChunkDefinition> {
        val chunks = mutableListOf<ChunkDefinition>()
        if (title.isNotBlank()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = "METADATA_TITLE",
                    chapterOrder = -1,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = 0,
                    chunkType = "TITLE",
                    text = title
                )
            )
        }

        if (description.isNotBlank()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = "METADATA_DESCRIPTION",
                    chapterOrder = -1,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = 1,
                    chunkType = "DESCRIPTION",
                    text = description
                )
            )
        }
        return chunks
    }

    fun generateChunksForChapter(
        chapterId: String,
        chapterOrder: Int,
        chapterTitle: String,
        paragraphs: List<String>,
        maxChunkSize: Int = 4200
    ): List<ChunkDefinition> {
        val chunks = mutableListOf<ChunkDefinition>()
        var chunkIndex = 0

        // Chapter Title Chunk
        if (chapterTitle.isNotBlank()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = chapterId,
                    chapterOrder = chapterOrder,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = chunkIndex++,
                    chunkType = "CHAPTER_TITLE",
                    text = chapterTitle
                )
            )
        }

        // Chapter Body Paragraph Chunks
        var currentParagraphs = mutableListOf<String>()
        var currentLength = 0
        var paragraphStartIdx = 0

        for (i in paragraphs.indices) {
            val p = paragraphs[i]
            val pLength = p.length + 2 // accounting for "\n\n"

            if (currentLength + pLength > maxChunkSize && currentParagraphs.isNotEmpty()) {
                chunks.add(
                    ChunkDefinition(
                        chapterId = chapterId,
                        chapterOrder = chapterOrder,
                        chunkId = UUID.randomUUID().toString(),
                        chunkOrder = chunkIndex++,
                        chunkType = "CHAPTER_BODY",
                        text = currentParagraphs.joinToString("\n\n"),
                        paragraphStartIdx = paragraphStartIdx,
                        paragraphEndIdx = i - 1
                    )
                )
                currentParagraphs = mutableListOf()
                currentLength = 0
                paragraphStartIdx = i
            }

            currentParagraphs.add(p)
            currentLength += pLength
        }

        if (currentParagraphs.isNotEmpty()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = chapterId,
                    chapterOrder = chapterOrder,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = chunkIndex++,
                    chunkType = "CHAPTER_BODY",
                    text = currentParagraphs.joinToString("\n\n"),
                    paragraphStartIdx = paragraphStartIdx,
                    paragraphEndIdx = paragraphs.size - 1
                )
            )
        }

        return chunks
    }

    fun generateChunks(
        parsedEpub: ParsedEpub,
        maxChunkSize: Int = 4200
    ): List<ChunkDefinition> {
        val chunks = mutableListOf<ChunkDefinition>()
        chunks.addAll(generateChunksForMetadata(parsedEpub.metadata.title, parsedEpub.metadata.description))
        for (chapter in parsedEpub.chapters) {
            chunks.addAll(
                generateChunksForChapter(
                    chapterId = chapter.chapterId,
                    chapterOrder = chapter.chapterOrder,
                    chapterTitle = chapter.title,
                    paragraphs = chapter.translatableParagraphs,
                    maxChunkSize = maxChunkSize
                )
            )
        }
        return chunks
    }
}
