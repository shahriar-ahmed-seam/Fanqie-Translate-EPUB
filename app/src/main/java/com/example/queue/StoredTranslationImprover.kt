package com.example.queue

import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.TranslationChunkEntity
import com.example.translation.TranslationException
import com.example.translation.TranslationQuality
import com.example.translation.TranslationQualityException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixes, in place, translations saved by earlier versions that still have Chinese in them:
 * paragraphs Google sent back untranslated, Chinese words inside English sentences, and titles.
 * Only the Chinese parts are translated again; a chunk's text is replaced only by one with less
 * Chinese, and only while the chunk is still completed. Books keep their status and stay readable.
 *
 * [waitUntilAllowed] is called before each chunk (it waits while books are being translated or the
 * phone is offline). Stops early, without finishing, when Google limits or blocks requests.
 */
class StoredTranslationImprover(
    private val database: AppDatabase,
    private val translator: ChunkTranslator,
    private val parallelism: Int = 3,
    private val waitUntilAllowed: suspend () -> Unit
) {
    data class Summary(val checked: Int, val improved: Int, val finished: Boolean)

    suspend fun run(): Summary {
        val checked = AtomicInteger(0)
        val improved = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        val permits = Semaphore(parallelism)
        for (job in database.jobDao().getAllJobsOnce()) {
            if (job.status == "CANCELLED") continue
            var afterChapter = Int.MIN_VALUE
            var afterChunk = Int.MIN_VALUE
            val jobImproved = AtomicBoolean(false)
            while (!stopped.get()) {
                val page = database.chunkDao().getChunksPageByJob(job.id, afterChapter, afterChunk, PAGE_SIZE)
                if (page.isEmpty()) break
                val candidates = page.filter(::hasChineseLeft)
                coroutineScope {
                    candidates.map { chunk ->
                        async {
                            permits.withPermit {
                                if (stopped.get()) return@withPermit
                                waitUntilAllowed()
                                checked.incrementAndGet()
                                when (improve(chunk)) {
                                    Outcome.IMPROVED -> {
                                        improved.incrementAndGet()
                                        jobImproved.set(true)
                                    }
                                    Outcome.STOP -> stopped.set(true)
                                    Outcome.UNCHANGED -> Unit
                                }
                            }
                        }
                    }.awaitAll()
                }
                val last = page.last()
                afterChapter = last.chapterOrder
                afterChunk = last.chunkOrder
                if (page.size < PAGE_SIZE) break
            }
            // The next export is built again with the fixed text.
            if (jobImproved.get()) database.jobDao().clearExportCache(job.id)
            if (stopped.get()) break
        }
        return Summary(checked.get(), improved.get(), finished = !stopped.get())
    }

    private enum class Outcome { IMPROVED, UNCHANGED, STOP }

    private suspend fun improve(chunk: TranslationChunkEntity): Outcome {
        val stored = TranslationQuality.usable(chunk.translatedText, chunk.sourceText) ?: return Outcome.UNCHANGED
        val before = TranslationQuality.hanCount(stored)
        val result = translator.retranslate(chunk.chunkType, chunk.sourceText, stored)
        val error = result.exceptionOrNull()
        if (error is TranslationException && (error.isRateLimited || error.isPermanent || error.retryAfterMs != null)) {
            Log.i(TAG, "Stopping the clean-up for now: ${error.message}")
            return Outcome.STOP
        }
        val better = (result.getOrNull() ?: (error as? TranslationQualityException)?.bestText)
            ?.takeIf { it.isNotBlank() && !TranslationQuality.isJsonJunk(it) && TranslationQuality.hanCount(it) < before }
            ?: return Outcome.UNCHANGED
        return if (database.chunkDao().replaceCompletedTranslation(chunk.id, better) > 0) Outcome.IMPROVED else Outcome.UNCHANGED
    }

    companion object {
        private const val TAG = "TranslationCleanUp"
        private const val PAGE_SIZE = 200

        /** A completed chunk whose saved translation still has Chinese that needs translating. */
        fun hasChineseLeft(chunk: TranslationChunkEntity): Boolean {
            if (chunk.status != "COMPLETED" || !TranslationQuality.needsTranslation(chunk.sourceText)) return false
            val stored = TranslationQuality.usable(chunk.translatedText, chunk.sourceText) ?: return false
            return TranslationQuality.hanCount(stored) > 0
        }
    }
}
