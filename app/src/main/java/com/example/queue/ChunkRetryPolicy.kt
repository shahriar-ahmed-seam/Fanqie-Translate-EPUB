package com.example.queue

import com.example.translation.TranslationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** First pause after an empty or untranslated answer; doubles with each further one. */
internal const val SOFT_FAILURE_BACKOFF_MS = 2_000L

/** Result of translating one chunk within one claim. */
data class ChunkAttemptOutcome(
    val translatedText: String?,
    val error: Throwable?,
    /** Failed requests that count against the chunk's retry budget. */
    val failedAttempts: Int,
    /** True when the claim gave up only because the service kept rate-limiting (no budget used). */
    val deferredByRateLimit: Boolean = false
) {
    val isSuccess: Boolean get() = translatedText != null
}

/**
 * Translates with a bounded number of failed attempts. A chunk's whole life is limited to
 * `maxRetries` failed requests: the caller passes the budget that is left, so retries no longer
 * multiply (previously up to maxRetries² requests per chunk).
 *
 * Rate-limit responses (HTTP 429) are waited out without using the budget, up to
 * [maxRateLimitWaits] times per claim; after that the chunk is handed back for later.
 */
suspend fun translateWithRetry(
    remainingBudget: Int,
    maxRateLimitWaits: Int = 3,
    sleep: suspend (Long) -> Unit = { delay(it) },
    jitter: (LongRange) -> Long = { it.random() },
    translate: suspend () -> Result<String>
): ChunkAttemptOutcome {
    val budget = remainingBudget.coerceAtLeast(1)
    var failures = 0
    var rateLimitWaits = 0
    var lastError: Throwable? = null
    while (true) {
        val result = try {
            translate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val text = result.getOrNull()
        if (result.isSuccess && !text.isNullOrBlank()) return ChunkAttemptOutcome(text, null, failures)

        lastError = result.exceptionOrNull() ?: IllegalStateException("Empty translation response")
        val ex = lastError as? TranslationException
        if (ex?.isRateLimited == true) {
            if (rateLimitWaits >= maxRateLimitWaits) {
                return ChunkAttemptOutcome(null, lastError, failures, deferredByRateLimit = true)
            }
            rateLimitWaits++
            sleep((ex.retryAfterMs ?: (2_000L * rateLimitWaits)) + jitter(100L..500L))
            continue
        }

        failures++
        if (ex?.isPermanent == true || failures >= budget) return ChunkAttemptOutcome(null, lastError, failures)
        val backoff = when {
            ex?.statusCode in 500..599 -> (1_000L * (1L shl (failures - 1))).coerceAtMost(8_000L) + jitter(50L..250L)
            // Empty or untranslated answers: Google is overloaded. Give it longer each time.
            ex?.isSoftFailure == true -> (SOFT_FAILURE_BACKOFF_MS * (1L shl (failures - 1))).coerceAtMost(16_000L) + jitter(100L..400L)
            else -> 500L * failures + jitter(50L..200L)
        }
        // The service asked for a longer wait (for example after answering with a web page).
        sleep(maxOf(backoff, ex?.retryAfterMs ?: 0L))
    }
}
