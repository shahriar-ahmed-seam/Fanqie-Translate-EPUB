package com.example.tomato

import android.content.Context
import android.net.Uri
import com.example.data.db.AppDatabase
import com.example.library.LocalBookImporter
import com.example.queue.TranslationQueueManager
import com.example.service.TranslationService
import com.example.tomato.data.TomatoDatabase
import com.example.tomato.engine.TomatoEngineClient
import java.io.File

/** Wires the Tomato feature (created on first use of the Tomato tab, never at app start). */
class TomatoGraph(context: Context, appDatabase: AppDatabase, queueManager: () -> TranslationQueueManager) {
    private val appContext = context.applicationContext
    val installer = TomatoEngineInstaller(appContext)
    val engineClient = TomatoEngineClient(appContext) { installer.installed()?.path }
    val repository = TomatoRepository(
        context = appContext,
        database = TomatoDatabase.get(appContext),
        engine = ProcessEngineGateway(engineClient),
        installer = installer,
        library = AppLibraryActions(appContext, appDatabase, queueManager),
        onDownloadStarted = { TomatoDownloadService.start(appContext) }
    )

    companion object {
        /** True if the Tomato tab was ever used (its database exists). */
        fun wasUsed(context: Context): Boolean = context.getDatabasePath("tomato.db").exists()
    }
}

private class AppLibraryActions(
    private val context: Context,
    private val database: AppDatabase,
    private val queueManager: () -> TranslationQueueManager
) : TomatoLibraryActions {
    override suspend fun addToBookshelf(epub: File, fileName: String): String =
        LocalBookImporter(context, database).import(Uri.fromFile(epub), fileName).id

    override suspend fun translate(epub: File, fileName: String): String {
        val (book, _) = queueManager().enqueueEpub(Uri.fromFile(epub), fileName)
        TranslationService.start(context)
        return book.id
    }

    override suspend fun exists(libraryBookId: String): Boolean = database.bookDao().getBookById(libraryBookId) != null
}
