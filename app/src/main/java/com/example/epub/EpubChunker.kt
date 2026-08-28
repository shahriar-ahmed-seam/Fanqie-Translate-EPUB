package com.example.epub

import java.util.UUID

object EpubChunker {

    fun generateChunks(
        parsedEpub: ParsedEpub,
        maxChunkSize: Int = 4200
    ): List<ChunkDefinition> {
        val chunks = mutableListOf<ChunkDefinition>()

        // 1. Metadata Chunks (Chapter Order = -1)
        if (parsedEpub.metadata.title.isNotBlank()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = "METADATA_TITLE",
                    chapterOrder = -1,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = 0,
                    chunkType = "TITLE",
                    text = parsedEpub.metadata.title
                )
            )
        }

        if (parsedEpub.metadata.description.isNotBlank()) {
            chunks.add(
                ChunkDefinition(
                    chapterId = "METADATA_DESCRIPTION",
                    chapterOrder = -1,
                    chunkId = UUID.randomUUID().toString(),
                    chunkOrder = 1,
                    chunkType = "DESCRIPTION",
                    text = parsedEpub.metadata.description
                )
            )
        }

        // 2. Chapters Chunks
        for (chapter in parsedEpub.chapters) {
            var chunkIndex = 0

            // Chapter Title Chunk
            if (chapter.title.isNotBlank()) {
                chunks.add(
                    ChunkDefinition(
                        chapterId = chapter.chapterId,
                        chapterOrder = chapter.chapterOrder,
                        chunkId = UUID.randomUUID().toString(),
                        chunkOrder = chunkIndex++,
                        chunkType = "CHAPTER_TITLE",
                        text = chapter.title
                    )
                )
            }

            // Chapter Body Paragraph Chunks
            val paragraphs = chapter.translatableParagraphs
            var currentParagraphs = mutableListOf<String>()
            var currentLength = 0
            var paragraphStartIdx = 0

            for (i in paragraphs.indices) {
                val p = paragraphs[i]
                val pLength = p.length + 2 // accounting for "\n\n"

                if (currentLength + pLength > maxChunkSize && currentParagraphs.isNotEmpty()) {
                    // Flush current chunk
                    chunks.add(
                        ChunkDefinition(
                            chapterId = chapter.chapterId,
                            chapterOrder = chapter.chapterOrder,
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
                        chapterId = chapter.chapterId,
                        chapterOrder = chapter.chapterOrder,
                        chunkId = UUID.randomUUID().toString(),
                        chunkOrder = chunkIndex++,
                        chunkType = "CHAPTER_BODY",
                        text = currentParagraphs.joinToString("\n\n"),
                        paragraphStartIdx = paragraphStartIdx,
                        paragraphEndIdx = paragraphs.size - 1
                    )
                )
            }
        }

        return chunks
    }
}
