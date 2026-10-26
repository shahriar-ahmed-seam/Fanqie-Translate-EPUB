package com.example.tomato.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Where a Tomato book is in its life: saved for later, downloading, or downloaded. */
object TomatoState {
    const val LATER = "LATER"
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DOWNLOADED = "DOWNLOADED"
    /** Downloaded, but some chapters failed (the EPUB has the rest). */
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"

    val ACTIVE = setOf(QUEUED, DOWNLOADING)
    val HAS_FILE = setOf(DOWNLOADED, PARTIAL)
}

/** What happened to a downloaded book in the app's library. */
object TomatoLibraryKind {
    const val NONE = "NONE"
    /** Added to the bookshelf as a local (Chinese) book. */
    const val LOCAL = "LOCAL"
    /** Sent to the translation queue (which also puts it on the bookshelf). */
    const val TRANSLATION = "TRANSLATION"
}

@Entity(tableName = "tomato_books")
data class TomatoBookEntity(
    @PrimaryKey val bookId: String,
    val title: String,
    val author: String,
    val abstractText: String,
    val coverUrl: String,
    val category: String,
    val serialStatus: String,
    val wordCount: Long,
    val chapterCount: Int,
    val lastChapterTime: Long,
    val state: String,
    val jobId: String? = null,
    val progressPercent: Int = 0,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val message: String? = null,
    val epubPath: String? = null,
    val epubSizeBytes: Long = 0,
    val missingChapters: Int = 0,
    val downloadedAt: Long = 0,
    val libraryBookId: String? = null,
    val libraryKind: String = TomatoLibraryKind.NONE,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface TomatoBookDao {
    @Query("SELECT * FROM tomato_books ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TomatoBookEntity>>

    @Query("SELECT * FROM tomato_books WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<TomatoBookEntity?>

    @Query("SELECT * FROM tomato_books WHERE bookId = :bookId")
    suspend fun get(bookId: String): TomatoBookEntity?

    @Query("SELECT * FROM tomato_books")
    suspend fun getAllOnce(): List<TomatoBookEntity>

    @Query("SELECT * FROM tomato_books WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getActive(): List<TomatoBookEntity>

    @Query("SELECT * FROM tomato_books WHERE state IN ('QUEUED', 'DOWNLOADING')")
    fun observeActive(): Flow<List<TomatoBookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: TomatoBookEntity)

    @Query("DELETE FROM tomato_books WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("UPDATE tomato_books SET libraryBookId = NULL, libraryKind = 'NONE', updatedAt = :now WHERE libraryBookId = :libraryBookId")
    suspend fun unlinkLibraryBook(libraryBookId: String, now: Long = System.currentTimeMillis())
}

/**
 * Tomato downloads live in their own small database, so this feature can never interfere with (or
 * need a migration of) the library database.
 */
@Database(entities = [TomatoBookEntity::class], version = 1, exportSchema = true)
abstract class TomatoDatabase : RoomDatabase() {
    abstract fun books(): TomatoBookDao

    companion object {
        @Volatile
        private var instance: TomatoDatabase? = null

        fun get(context: Context): TomatoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TomatoDatabase::class.java, "tomato.db")
                .build()
                .also { instance = it }
        }

        fun inMemory(context: Context): TomatoDatabase =
            Room.inMemoryDatabaseBuilder(context, TomatoDatabase::class.java).allowMainThreadQueries().build()
    }
}
