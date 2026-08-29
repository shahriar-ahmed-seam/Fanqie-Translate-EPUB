package com.example.epub

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubStreamingTest {

    @Test
    fun testParseQuickInfoAndExtractChapterStreaming() {
        val tempEpub = File.createTempFile("test_novel_", ".epub")
        try {
            // Build a valid test EPUB with 5 chapters
            val containerXml = """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles>
                      <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                   </rootfiles>
                </container>""".trimIndent()

            val opfXml = """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Great Scalable Novel</dc:title>
                    <dc:creator>Famous Author</dc:creator>
                    <dc:description>A novel with many chapters</dc:description>
                    <dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch4" href="ch4.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch5" href="ch5.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                    <itemref idref="ch3"/>
                    <itemref idref="ch4"/>
                    <itemref idref="ch5"/>
                  </spine>
                </package>""".trimIndent()

            ZipOutputStream(tempEpub.outputStream()).use { zos ->
                // container
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(containerXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // opf
                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zos.write(opfXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // chapters
                for (i in 1..5) {
                    val chapterContent = """<?xml version="1.0" encoding="utf-8"?>
                        <html xmlns="http://www.w3.org/1999/xhtml">
                        <head><title>Chapter $i Title</title></head>
                        <body>
                            <h1>Chapter $i: The Beginning of Part $i</h1>
                            <p>Paragraph 1 of chapter $i with important text.</p>
                            <p>Paragraph 2 of chapter $i with more details.</p>
                        </body>
                        </html>""".trimIndent()
                    zos.putNextEntry(ZipEntry("OEBPS/ch$i.xhtml"))
                    zos.write(chapterContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            // 1. Test Quick Info (only metadata and spine, no chapters in memory)
            val quickInfo = EpubParser.parseQuickInfo(tempEpub)
            assertEquals("Great Scalable Novel", quickInfo.metadata.title)
            assertEquals("Famous Author", quickInfo.metadata.author)
            assertEquals("A novel with many chapters", quickInfo.metadata.description)
            assertEquals(5, quickInfo.spine.size)
            assertEquals("OEBPS", quickInfo.opfDirectory)

            // 2. Test Metadata Chunk Generation
            val metaChunks = EpubChunker.generateChunksForMetadata(quickInfo.metadata.title, quickInfo.metadata.description)
            assertEquals(2, metaChunks.size)
            assertEquals("TITLE", metaChunks[0].chunkType)
            assertEquals("DESCRIPTION", metaChunks[1].chunkType)

            // 3. Test Streaming Single Chapter Extraction
            val zip = ZipFile(tempEpub)
            var totalChunks = metaChunks.size
            try {
                quickInfo.spine.forEachIndexed { index, item ->
                    val chapterData = EpubParser.extractChapter(zip, quickInfo.opfDirectory, item, index)
                    assertEquals(index, chapterData.chapterOrder)
                    assertTrue(chapterData.title.contains("Chapter ${index + 1}"))
                    assertEquals(3, chapterData.translatableParagraphs.size)

                    val chapterChunks = EpubChunker.generateChunksForChapter(
                        chapterId = chapterData.chapterId,
                        chapterOrder = index,
                        chapterTitle = chapterData.title,
                        paragraphs = chapterData.translatableParagraphs,
                        maxChunkSize = 4200
                    )

                    assertEquals(2, chapterChunks.size) // 1 title chunk + 1 body chunk
                    assertEquals("CHAPTER_TITLE", chapterChunks[0].chunkType)
                    assertEquals("CHAPTER_BODY", chapterChunks[1].chunkType)
                    totalChunks += chapterChunks.size
                }
            } finally {
                zip.close()
            }

            assertEquals(2 + 5 * 2, totalChunks) // 2 metadata + 10 chapter chunks
        } finally {
            tempEpub.delete()
        }
    }
}
