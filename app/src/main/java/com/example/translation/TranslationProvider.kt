package com.example.translation

interface TranslationProvider {
    val name: String
    suspend fun translate(text: String, sourceLang: String = "zh", targetLang: String = "en"): Result<String>
}
