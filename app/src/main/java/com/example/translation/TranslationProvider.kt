package com.example.translation

open class TranslationException(
    message: String,
    val statusCode: Int? = null,
    val isRateLimited: Boolean = false,
    val isPermanent: Boolean = false,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
    /**
     * The service answered, but with nothing usable (empty `[""]`, junk, or the Chinese sent back).
     * Google does this when it is overloaded, so these are retried after a longer pause.
     */
    val isSoftFailure: Boolean = false
) : Exception(message, cause)

/**
 * A chunk came back with some paragraphs still in Chinese (or empty) even after they were retried
 * one by one. [bestText] is the best translation so far (every paragraph present, the bad ones as
 * they came back); it is kept only as a last resort when the chunk runs out of retries.
 */
class TranslationQualityException(
    message: String,
    val bestText: String?,
    val badParagraphs: Int,
    val totalParagraphs: Int
) : TranslationException(message, isSoftFailure = true)

interface TranslationProvider {
    val name: String
    suspend fun translate(text: String, sourceLang: String = "zh", targetLang: String = "en"): Result<String>
}
