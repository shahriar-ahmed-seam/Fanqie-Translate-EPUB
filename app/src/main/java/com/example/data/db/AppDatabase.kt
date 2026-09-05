package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create indexes on translation_jobs
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_jobs_status` ON `translation_jobs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_jobs_bookId` ON `translation_jobs` (`bookId`)")

        // Create index on chapters
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId_chapterOrder` ON `chapters` (`bookId`, `chapterOrder`)")

        // Create indexes on translation_chunks
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId` ON `translation_chunks` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_status` ON `translation_chunks` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_status` ON `translation_chunks` (`jobId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chapterId` ON `translation_chunks` (`jobId`, `chapterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chapterOrder_chunkOrder` ON `translation_chunks` (`jobId`, `chapterOrder`, `chunkOrder`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_bookId_chunkType` ON `translation_chunks` (`bookId`, `chunkType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_chunks_jobId_chunkType` ON `translation_chunks` (`jobId`, `chunkType`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `bookType` TEXT NOT NULL DEFAULT 'TRANSLATION'")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `localFilePath` TEXT DEFAULT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Library Groups
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_groups` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `isSystemGroup` INTEGER NOT NULL,
                `systemKey` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_groups_sortOrder` ON `library_groups` (`sortOrder`)")

        // 2. Book Group Cross Ref
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `book_group_cross_ref` (
                `bookId` TEXT NOT NULL,
                `groupId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`bookId`, `groupId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_group_cross_ref_bookId` ON `book_group_cross_ref` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_group_cross_ref_groupId` ON `book_group_cross_ref` (`groupId`)")

        // 3. Chapter Bookmarks
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_bookmarks` (
                `id` TEXT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_bookmarks_bookId_chapterId` ON `chapter_bookmarks` (`bookId`, `chapterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_bookmarks_bookId` ON `chapter_bookmarks` (`bookId`)")

        // 4. Seed default groups
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO `library_groups` (`id`, `name`, `sortOrder`, `isSystemGroup`, `systemKey`, `createdAt`)
            VALUES ('default_translated', 'Translated', 1, 1, 'TRANSLATED', $now)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `library_groups` (`id`, `name`, `sortOrder`, `isSystemGroup`, `systemKey`, `createdAt`)
            VALUES ('default_local', 'Local Books', 2, 1, 'LOCAL', $now)
            """.trimIndent()
        )

        // 5. Populate existing books into groups
        db.execSQL(
            """
            INSERT OR IGNORE INTO `book_group_cross_ref` (`bookId`, `groupId`, `createdAt`)
            SELECT `id`, 'default_translated', $now FROM `books` WHERE `bookType` = 'TRANSLATION' OR `bookType` IS NULL OR `bookType` = ''
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `book_group_cross_ref` (`bookId`, `groupId`, `createdAt`)
            SELECT `id`, 'default_local', $now FROM `books` WHERE `bookType` = 'LOCAL'
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tts_rules` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `ruleType` TEXT NOT NULL,
                `pattern` TEXT NOT NULL,
                `replacement` TEXT NOT NULL,
                `isRegex` INTEGER NOT NULL,
                `caseSensitive` INTEGER NOT NULL,
                `wholeWord` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `isEnabled` INTEGER NOT NULL,
                `bookId` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tts_rules_ruleType` ON `tts_rules` (`ruleType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tts_rules_bookId` ON `tts_rules` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tts_rules_sortOrder` ON `tts_rules` (`sortOrder`)")
    }
}

val DB_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        seedDefaultGroups(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        seedDefaultGroups(db)
    }

    private fun seedDefaultGroups(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO `library_groups` (`id`, `name`, `sortOrder`, `isSystemGroup`, `systemKey`, `createdAt`)
            VALUES ('default_translated', 'Translated', 1, 1, 'TRANSLATED', $now)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `library_groups` (`id`, `name`, `sortOrder`, `isSystemGroup`, `systemKey`, `createdAt`)
            VALUES ('default_local', 'Local Books', 2, 1, 'LOCAL', $now)
            """.trimIndent()
        )
    }
}

@Database(
    entities = [
        BookEntity::class,
        TranslationJobEntity::class,
        ChapterEntity::class,
        TranslationChunkEntity::class,
        LibraryGroupEntity::class,
        BookGroupCrossRefEntity::class,
        ChapterBookmarkEntity::class,
        TtsRuleEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun jobDao(): TranslationJobDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chunkDao(): TranslationChunkDao
    abstract fun groupDao(): LibraryGroupDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun ttsRuleDao(): TtsRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "epub_translator.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                 .addCallback(DB_CALLBACK)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

