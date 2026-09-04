package com.example.translation

class TranslationException(
    message: String,
    val statusCode: Int? = null,
    val isRateLimited: Boolean = false,
    val isPermanent: Boolean = false,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)

interface TranslationProvider {
    val name: String
    suspend fun translate(text: String, sourceLang: String = "zh", targetLang: String = "en"): Result<String>
}

