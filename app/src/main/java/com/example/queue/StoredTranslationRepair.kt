package com.example.queue

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.TranslationChunkEntity
import com.example.translation.TranslationQuality

/**
 * Finds translations saved by earlier versions that can't be shown at all and sends them back to
 * the queue: chunks stored as `[""]` or empty (whole chapters showed only that). Language codes glued
 * to otherwise good text ("…zh-CN") are removed in place. Translations with some Chinese left are
 * not touched here; [StoredTranslationImprover] fixes them in place, so their books stay completed.
 *
 * Each book is changed in one transaction, so stopping halfway never leaves chunks waiting in a book
 * marked completed.
 */
class StoredTranslationRepair(private val database: AppDatabase) {

    data class Summary(val scanned: Int, val requeued: Int, val cleaned: Int, val requeuedJobs: Set<String>)

    sealed interface Verdict {
        data object Keep : Verdict
        data class Clean(val text: String) : Verdict
        data object Retranslate : Verdict
    }

    suspend fun run(): Summary {
        var scanned = 0
        var requeued = 0
        var cleaned = 0
        val requeuedJobs = mutableSetOf<String>()
        for (job in database.jobDao().getAllJobsOnce()) {
            if (job.status == "CANCELLED") continue
            val junk = mutableListOf<String>()
            val cleanings = mutableListOf<Pair<String, String>>()
            var afterChapter = Int.MIN_VALUE
            var afterChunk = Int.MIN_VALUE
            while (true) {
                val page = database.chunkDao().getChunksPageByJob(job.id, afterChapter, afterChunk, PAGE_SIZE)
                if (page.isEmpty()) break
                for (chunk in page) {
                    if (chunk.status != "COMPLETED") continue
                    scanned++
                    when (val verdict = verdict(chunk)) {
                        Verdict.Keep -> Unit
                        is Verdict.Clean -> cleanings += chunk.id to verdict.text
                        Verdict.Retranslate -> junk += chunk.id
                    }
                }
                val last = page.last()
                afterChapter = last.chapterOrder
                afterChunk = last.chunkOrder
                if (page.size < PAGE_SIZE) break
            }
            if (junk.isEmpty() && cleanings.isEmpty()) continue
            database.withTransaction {
                var jobCleaned = 0
                for ((id, text) in cleanings) jobCleaned += database.chunkDao().replaceCompletedTranslation(id, text)
                var jobRequeued = 0
                for (id in junk) jobRequeued += database.chunkDao().requeueCompletedChunk(id, REASON)
                if (jobRequeued > 0) {
                    // Finished books go back in the queue, behind the books queued now. Paused books
                    // stay paused (they pick the chunks up when resumed); running ones just continue.
                    database.jobDao().requeueIfFinished(job.id)
                    requeuedJobs += job.id
                }
                if (jobCleaned > 0 || jobRequeued > 0) database.jobDao().clearExportCache(job.id)
                cleaned += jobCleaned
                requeued += jobRequeued
            }
        }
        return Summary(scanned, requeued, cleaned, requeuedJobs)
    }

    companion object {
        private const val PAGE_SIZE = 200
        const val REASON = "Translating again: the earlier translation was empty"

        fun verdict(chunk: TranslationChunkEntity): Verdict {
            val source = chunk.sourceText
            val stored = chunk.translatedText
            if (!TranslationQuality.needsTranslation(source)) {
                return if (TranslationQuality.usable(stored, source) == null) Verdict.Clean(source) else Verdict.Keep
            }
            val usable = TranslationQuality.usable(stored, source) ?: return Verdict.Retranslate
            return if (usable != stored?.trim()) Verdict.Clean(usable) else Verdict.Keep
        }
    }
}
