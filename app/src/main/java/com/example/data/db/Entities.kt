package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String, // UUID
    val title: String,
    val author: String,
    val description: String,
    val coverPath: String?,
    val originalUri: String,
    val originalFileName: String,
    val chapterCount: Int,
    val totalChunks: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceLanguage: String = "zh",
    val targetLanguage: String = "en",
    val bookType: String = BookType.TRANSLATION,
    val localFilePath: String? = null
) {
    val isLocalBook: Boolean get() = bookType == BookType.LOCAL
}

object BookType {
    const val TRANSLATION = "TRANSLATION"
    const val LOCAL = "LOCAL"
}

@Entity(
    tableName = "translation_jobs",
    indices = [
        Index(value = ["status"], name = "index_translation_jobs_status"),
        Index(value = ["bookId"], name = "index_translation_jobs_bookId")
    ]
)
data class TranslationJobEntity(
    @PrimaryKey
    val id: String, // UUID (jobId)
    val bookId: String,
    val status: String, // QUEUED, TRANSLATING, PAUSED, COMPLETED, FAILED, CANCELLED
    val progress: Float = 0f,
    val completedChunks: Int = 0,
    val failedChunks: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val exportedUri: String? = null,
    val exportedFileName: String? = null
)

@Entity(
    tableName = "chapters",
    indices = [
        Index(value = ["bookId", "chapterOrder"], name = "index_chapters_bookId_chapterOrder")
    ]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String, // UUID (chapterId)
    val bookId: String,
    val chapterOrder: Int, // 0-indexed spine order
    val originalHref: String,
    val title: String,
    val chunkCount: Int = 0
)

@Entity(
    tableName = "translation_chunks",
    indices = [
        Index(value = ["jobId"], name = "index_translation_chunks_jobId"),
        Index(value = ["status"], name = "index_translation_chunks_status"),
        Index(value = ["jobId", "status"], name = "index_translation_chunks_jobId_status"),
        Index(value = ["jobId", "chapterId"], name = "index_translation_chunks_jobId_chapterId"),
        Index(value = ["jobId", "chapterOrder", "chunkOrder"], name = "index_translation_chunks_jobId_chapterOrder_chunkOrder"),
        Index(value = ["bookId", "chunkType"], name = "index_translation_chunks_bookId_chunkType"),
        Index(value = ["jobId", "chunkType"], name = "index_translation_chunks_jobId_chunkType")
    ]
)
data class TranslationChunkEntity(
    @PrimaryKey
    val id: String, // UUID (chunkId)
    val jobId: String,
    val bookId: String,
    val chapterId: String,
    val chapterOrder: Int,
    val chunkOrder: Int, // 0-indexed chunk within chapter or metadata
    val chunkType: String = "CHAPTER_BODY", // TITLE, DESCRIPTION, CHAPTER_TITLE, CHAPTER_BODY
    val sourceText: String,
    val translatedText: String? = null,
    val status: String = "PENDING", // PENDING, TRANSLATING, COMPLETED, FAILED
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class JobChunkCount(
    val jobId: String,
    val count: Int
)

@Entity(
    tableName = "library_groups",
    indices = [
        Index(value = ["sortOrder"], name = "index_library_groups_sortOrder")
    ]
)
data class LibraryGroupEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val isSystemGroup: Boolean = false,
    val systemKey: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "book_group_cross_ref",
    primaryKeys = ["bookId", "groupId"],
    indices = [
        Index(value = ["bookId"], name = "index_book_group_cross_ref_bookId"),
        Index(value = ["groupId"], name = "index_book_group_cross_ref_groupId")
    ]
)
data class BookGroupCrossRefEntity(
    val bookId: String,
    val groupId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapter_bookmarks",
    indices = [
        Index(value = ["bookId", "chapterId"], unique = true, name = "index_chapter_bookmarks_bookId_chapterId"),
        Index(value = ["bookId"], name = "index_chapter_bookmarks_bookId")
    ]
)
data class ChapterBookmarkEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val chapterId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tts_rules",
    indices = [
        Index(value = ["ruleType"], name = "index_tts_rules_ruleType"),
        Index(value = ["bookId"], name = "index_tts_rules_bookId"),
        Index(value = ["sortOrder"], name = "index_tts_rules_sortOrder")
    ]
)
data class TtsRuleEntity(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val ruleType: String, // "SKIP" or "REPLACE"
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val sortOrder: Int = 0,
    val isEnabled: Boolean = true,
    val bookId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

fun TtsRuleEntity.toModel(): com.example.tts.rule.TtsRule {
    val type = when (ruleType) {
        "SKIP_REGEX" -> com.example.tts.rule.TtsRuleType.SKIP_REGEX
        "REPLACE" -> com.example.tts.rule.TtsRuleType.REPLACE
        else -> if (isRegex) com.example.tts.rule.TtsRuleType.SKIP_REGEX else com.example.tts.rule.TtsRuleType.SKIP
    }
    return com.example.tts.rule.TtsRule(
        id = id,
        name = name,
        ruleType = type,
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex || type == com.example.tts.rule.TtsRuleType.SKIP_REGEX,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
        sortOrder = sortOrder,
        isEnabled = isEnabled,
        bookId = bookId,
        createdAt = createdAt
    )
}

fun com.example.tts.rule.TtsRule.toEntity(): TtsRuleEntity {
    return TtsRuleEntity(
        id = id,
        name = name,
        ruleType = ruleType.name,
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex || ruleType == com.example.tts.rule.TtsRuleType.SKIP_REGEX,
        caseSensitive = caseSensitive,
        wholeWord = wholeWord,
        sortOrder = sortOrder,
        isEnabled = isEnabled,
        bookId = bookId,
        createdAt = createdAt
    )
}


