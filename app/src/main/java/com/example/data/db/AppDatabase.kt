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

@Database(
    entities = [
        BookEntity::class,
        TranslationJobEntity::class,
        ChapterEntity::class,
        TranslationChunkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun jobDao(): TranslationJobDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chunkDao(): TranslationChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "epub_translator.db"
                ).addMigrations(MIGRATION_1_2)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
