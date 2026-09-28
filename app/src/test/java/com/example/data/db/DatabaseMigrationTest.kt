package com.example.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.scraper.data.ScraperRepository
import com.example.scraper.data.ScraperSchema
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrading from v1.0.x (database version 5) must keep every book, job, chunk, group, bookmark and
 * TTS rule, and add the scraper tables exactly as Room expects them (Room validates the schema
 * after the migration and throws if anything differs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/com.example.data.db.AppDatabase/$version.json").readText()).getJSONObject("database")

    /** Creates a database exactly as version [version] of the app created it, from the exported schema. */
    private fun createDatabaseAt(version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schema = schema(version)
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            seed(db)
            db.version = version
        }
    }

    @Test
    fun upgradeFromVersion5KeepsAllDataAndAddsTheScraper() = runBlocking {
        createDatabaseAt(5) { db ->
            db.execSQL("INSERT INTO books (id, title, author, description, coverPath, originalUri, originalFileName, chapterCount, totalChunks, createdAt, sourceLanguage, targetLanguage, bookType, localFilePath) VALUES ('b1', '书名', 'Author', 'Desc', NULL, 'content://x', 'x.epub', 2, 3, 10, 'zh', 'en', 'TRANSLATION', NULL)")
            db.execSQL("INSERT INTO books (id, title, author, description, coverPath, originalUri, originalFileName, chapterCount, totalChunks, createdAt, sourceLanguage, targetLanguage, bookType, localFilePath) VALUES ('b2', 'Local', 'A', '', NULL, 'content://y', 'y.epub', 1, 0, 11, 'en', 'en', 'LOCAL', '/data/y.epub')")
            db.execSQL("INSERT INTO translation_jobs (id, bookId, status, progress, completedChunks, failedChunks, totalChunks, errorMessage, startedAt, updatedAt, exportedUri, exportedFileName) VALUES ('j1', 'b1', 'PAUSED', 0.5, 1, 0, 3, NULL, 10, 12, NULL, NULL)")
            db.execSQL("INSERT INTO chapters (id, bookId, chapterOrder, originalHref, title, chunkCount) VALUES ('c1', 'b1', 0, 'c1.xhtml', '第一章', 2)")
            db.execSQL("INSERT INTO chapters (id, bookId, chapterOrder, originalHref, title, chunkCount) VALUES ('c2', 'b1', 1, 'c2.xhtml', '第二章', 1)")
            db.execSQL("INSERT INTO translation_chunks (id, jobId, bookId, chapterId, chapterOrder, chunkOrder, chunkType, sourceText, translatedText, status, retryCount, errorMessage, updatedAt) VALUES ('k1', 'j1', 'b1', 'c1', 0, 0, 'CHAPTER_TITLE', '第一章', 'Chapter One', 'COMPLETED', 0, NULL, 1)")
            db.execSQL("INSERT INTO translation_chunks (id, jobId, bookId, chapterId, chapterOrder, chunkOrder, chunkType, sourceText, translatedText, status, retryCount, errorMessage, updatedAt) VALUES ('k2', 'j1', 'b1', 'c1', 0, 1, 'CHAPTER_BODY', '正文', NULL, 'PENDING', 0, NULL, 1)")
            db.execSQL("INSERT INTO library_groups (id, name, sortOrder, isSystemGroup, systemKey, createdAt) VALUES ('default_translated', 'Translated', 1, 1, 'TRANSLATED', 1)")
            db.execSQL("INSERT INTO library_groups (id, name, sortOrder, isSystemGroup, systemKey, createdAt) VALUES ('default_local', 'Local Books', 2, 1, 'LOCAL', 1)")
            db.execSQL("INSERT INTO library_groups (id, name, sortOrder, isSystemGroup, systemKey, createdAt) VALUES ('g1', 'Favourites', 3, 0, NULL, 1)")
            db.execSQL("INSERT INTO book_group_cross_ref (bookId, groupId, createdAt) VALUES ('b1', 'g1', 1)")
            db.execSQL("INSERT INTO chapter_bookmarks (id, bookId, chapterId, createdAt) VALUES ('m1', 'b1', 'c2', 1)")
            db.execSQL("INSERT INTO tts_rules (id, name, ruleType, pattern, replacement, isRegex, caseSensitive, wholeWord, sortOrder, isEnabled, bookId, createdAt) VALUES ('r1', 'n', 'SKIP', 'ads', '', 0, 0, 0, 0, 1, NULL, 1)")
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(DB_CALLBACK)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening runs MIGRATION_5_6 and Room's schema validation.
            val b1 = db.bookDao().getBookById("b1")
            assertNotNull(b1)
            assertEquals("书名", b1!!.title)
            assertEquals(BookType.LOCAL, db.bookDao().getBookById("b2")!!.bookType)
            assertEquals("PAUSED", db.jobDao().getJobById("j1")!!.status)
            assertEquals(listOf("c1", "c2"), db.chapterDao().getChaptersByBook("b1").map { it.id })
            assertEquals(2, db.chunkDao().getTotalChunkCount("j1"))
            assertEquals("Chapter One", db.chunkDao().getChapterTitlesByBook("b1").single().translatedText)
            assertEquals(listOf("g1"), db.groupDao().getGroupIdsForBook("b1"))
            assertEquals(listOf("c2"), db.bookmarkDao().getBookmarkedChapterIds("b1"))
            assertEquals(1, db.ttsRuleDao().getAllRules().size)

            val groups = db.groupDao().getAllGroups()
            assertTrue(groups.any { it.id == SCRAPED_GROUP_ID && it.isSystemGroup && it.name == "Scraped" })
            assertTrue(groups.any { it.id == "g1" && it.name == "Favourites" })

            val repo = ScraperRepository(db, db.scrapeProjectDao(), db.scrapedChapterDao(), db.scrapeSessionDao(), db.siteStrategyDao(), db.browserHistoryDao())
            val project = repo.createProject("New novel", "https://site.com/n/chapter-1")
            assertEquals("New novel", repo.getProject(project.id)?.title)
        } finally {
            db.close()
        }
    }

    @Test
    fun scraperTableSqlMatchesTheExportedSchema() {
        val entities = schema(6).getJSONArray("entities")
        val expected = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            if (!table.startsWith("scrape_")) continue
            expected += entity.getString("createSql").replace("\${TABLE_NAME}", table)
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) expected += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
        }
        assertEquals(expected.sorted(), ScraperSchema.CREATE_STATEMENTS.sorted())
    }

    @Test
    fun freshInstallHasAllSystemGroups() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(DB_CALLBACK)
            .allowMainThreadQueries()
            .build()
        try {
            val ids = db.groupDao().getAllGroups().map { it.id }
            assertTrue(ids.containsAll(listOf("default_translated", "default_local", SCRAPED_GROUP_ID)))
        } finally {
            db.close()
        }
    }
}
