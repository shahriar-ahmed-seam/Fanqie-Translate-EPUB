package com.example.queue

import com.example.data.db.TranslationJobEntity
import com.example.translation.TranslationException
import com.example.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChunkRetryPolicyTest {

    private val noSleep: suspend (Long) -> Unit = {}
    private val noJitter: (LongRange) -> Long = { it.first }

    private fun run(budget: Int, responses: List<Result<String>>): Pair<ChunkAttemptOutcome, Int> = runBlocking {
        var calls = 0
        val outcome = translateWithRetry(remainingBudget = budget, sleep = noSleep, jitter = noJitter) {
            responses[minOf(calls++, responses.size - 1)]
        }
        outcome to calls
    }

    private val serverError = Result.failure<String>(TranslationException("503", statusCode = 503))
    private val rateLimited = Result.failure<String>(TranslationException("429", statusCode = 429, isRateLimited = true, retryAfterMs = 1_000))
    private val forbidden = Result.failure<String>(TranslationException("403", statusCode = 403, isPermanent = true))

    @Test
    fun succeedsAfterTransientFailures() {
        val (outcome, calls) = run(5, listOf(serverError, serverError, Result.success("Hello")))
        assertTrue(outcome.isSuccess)
        assertEquals("Hello", outcome.translatedText)
        assertEquals(2, outcome.failedAttempts)
        assertEquals(3, calls)
    }

    @Test
    fun neverMakesMoreRequestsThanTheRemainingBudget() {
        val (outcome, calls) = run(3, listOf(serverError))
        assertFalse(outcome.isSuccess)
        assertEquals(3, outcome.failedAttempts)
        assertEquals("previously up to maxRetries² requests per chunk", 3, calls)

        val (last, lastCalls) = run(1, listOf(serverError))
        assertEquals(1, last.failedAttempts)
        assertEquals(1, lastCalls)
    }

    @Test
    fun permanentErrorsStopImmediately() {
        val (outcome, calls) = run(5, listOf(forbidden))
        assertEquals(1, outcome.failedAttempts)
        assertEquals(1, calls)
    }

    @Test
    fun rateLimitsAreWaitedOutWithoutUsingTheBudget() {
        val (outcome, calls) = run(2, listOf(rateLimited, rateLimited, Result.success("OK")))
        assertTrue(outcome.isSuccess)
        assertEquals(0, outcome.failedAttempts)
        assertEquals(3, calls)
    }

    @Test
    fun persistentRateLimitingHandsTheChunkBackInsteadOfFailingIt() {
        val (outcome, calls) = run(5, listOf(rateLimited))
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.deferredByRateLimit)
        assertEquals(0, outcome.failedAttempts)
        assertEquals("3 waits + the final rate-limited response", 4, calls)
    }

    @Test
    fun blankTranslationsCountAsFailures() {
        val (outcome, _) = run(2, listOf(Result.success("   ")))
        assertFalse(outcome.isSuccess)
        assertNull(outcome.translatedText)
        assertEquals(2, outcome.failedAttempts)
    }

    @Test
    fun exportCacheIsOnlyReusedForAnUnchangedCompletedJob() {
        val file = File.createTempFile("export", ".epub").apply { writeText("epub"); setLastModified(10_000_000L) }
        try {
            val job = TranslationJobEntity(id = "j", bookId = "b", status = "COMPLETED", updatedAt = 9_000_000L)
            assertTrue(MainViewModel.isExportCacheValid(file, job))
            assertFalse("translation changed after the export (e.g. retry failed)", MainViewModel.isExportCacheValid(file, job.copy(updatedAt = 11_000_000L)))
            assertFalse("never reuse while translating", MainViewModel.isExportCacheValid(file, job.copy(status = "RUNNING")))
            file.writeText("")
            assertFalse(MainViewModel.isExportCacheValid(file, job))
        } finally {
            file.delete()
        }
    }
}
