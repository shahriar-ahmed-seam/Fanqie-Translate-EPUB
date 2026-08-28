package com.example.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class TomatoMTLProvider(
    private val timeoutSeconds: Long = 30L
) : TranslationProvider {

    override val name: String = "TomatoMTL (Google Engine)"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.success(text)
        }

        // Try TomatoMTL web endpoint first
        val tomatoResult = runCatching {
            tryTomatoMtlApi(text, sourceLang, targetLang)
        }

        if (tomatoResult.isSuccess && !tomatoResult.getOrNull().isNullOrBlank()) {
            return@withContext Result.success(tomatoResult.getOrNull()!!)
        }

        // Secondary fallback to Google translation engine (which TomatoMTL uses by default)
        val googleResult = runCatching {
            tryGoogleTranslateEngine(text, sourceLang, targetLang)
        }

        if (googleResult.isSuccess && !googleResult.getOrNull().isNullOrBlank()) {
            return@withContext Result.success(googleResult.getOrNull()!!)
        }

        val error = tomatoResult.exceptionOrNull() ?: googleResult.exceptionOrNull()
        ?: IllegalStateException("Translation returned empty text from all engines.")

        Log.e("TomatoMTLProvider", "Translation failed for chunk: ${error.message}", error)
        Result.failure(error)
    }

    private fun tryTomatoMtlApi(text: String, sourceLang: String, targetLang: String): String? {
        val payload = JSONObject().apply {
            put("text", text)
            put("engine", "google")
            put("from", if (sourceLang == "zh") "zh-CN" else sourceLang)
            put("to", targetLang)
            put("source_lang", if (sourceLang == "zh") "zh-CN" else sourceLang)
            put("target_lang", targetLang)
        }

        val requestBody = payload.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("https://tomatomtl.com/api/translate")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://tomatomtl.com/translate")
            .header("Origin", "https://tomatomtl.com")
            .header("Accept", "application/json, text/plain, */*")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("TomatoMTL HTTP ${response.code}: ${response.message}")
            }
            val responseBody = response.body?.string() ?: return null
            return parseTomatoMtlResponse(responseBody)
        }
    }

    private fun parseTomatoMtlResponse(responseBody: String): String? {
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            when {
                json.has("translatedText") -> return json.getString("translatedText")
                json.has("translation") -> return json.getString("translation")
                json.has("result") -> return json.getString("result")
                json.has("data") -> {
                    val data = json.get("data")
                    if (data is String) return data
                    if (data is JSONObject && data.has("translatedText")) return data.getString("translatedText")
                }
            }
        }
        return if (trimmed.isNotBlank() && !trimmed.startsWith("<")) trimmed else null
    }

    /**
     * TomatoMTL's underlying Google engine connector with paragraph splitting
     * to safely handle large chunks without character truncation.
     */
    private fun tryGoogleTranslateEngine(text: String, sourceLang: String, targetLang: String): String {
        val sl = if (sourceLang.startsWith("zh")) "zh-CN" else sourceLang
        val tl = targetLang

        // Split text by lines to ensure high-fidelity paragraph preservation
        val paragraphs = text.split("\n")
        val translatedParagraphs = mutableListOf<String>()

        var currentBatch = StringBuilder()
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) {
                if (currentBatch.isNotEmpty()) {
                    translatedParagraphs.add(executeGoogleRequest(currentBatch.toString(), sl, tl))
                    currentBatch = StringBuilder()
                }
                translatedParagraphs.add("")
            } else {
                if (currentBatch.length + paragraph.length > 1800) {
                    translatedParagraphs.add(executeGoogleRequest(currentBatch.toString(), sl, tl))
                    currentBatch = StringBuilder(paragraph)
                } else {
                    if (currentBatch.isNotEmpty()) currentBatch.append("\n")
                    currentBatch.append(paragraph)
                }
            }
        }

        if (currentBatch.isNotEmpty()) {
            translatedParagraphs.add(executeGoogleRequest(currentBatch.toString(), sl, tl))
        }

        return translatedParagraphs.joinToString("\n")
    }

    private fun executeGoogleRequest(text: String, sl: String, tl: String): String {
        val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encodedText"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "*/*")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Engine HTTP ${response.code}: ${response.message}")
            }
            val bodyString = response.body?.string() ?: throw IllegalStateException("Empty response body")
            val jsonArray = JSONArray(bodyString)
            val sentences = jsonArray.getJSONArray(0)
            val result = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                result.append(sentence.getString(0))
            }
            return result.toString()
        }
    }
}
