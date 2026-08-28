package com.example.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TomatoMTLProvider(
    private val timeoutSeconds: Long = 30L,
    maxConcurrentRequests: Int = 64
) : TranslationProvider {

    override val name: String = "TomatoMTL (Google Engine)"

    // Ensure OkHttp Dispatcher allows full concurrent requests without throttling to 5
    private val dispatcher = Dispatcher().apply {
        maxRequests = maxOf(128, maxConcurrentRequests * 2)
        maxRequestsPerHost = maxOf(128, maxConcurrentRequests * 2)
    }

    private val connectionPool = ConnectionPool(64, 5, TimeUnit.MINUTES)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.success(text)
        }

        val sl = if (sourceLang.startsWith("zh")) "zh-CN" else sourceLang
        val tl = if (targetLang.isBlank()) "en" else targetLang

        // If chunk is unexpectedly large (> 4200 chars), split by paragraph boundaries safely
        if (text.length > 4200) {
            val paragraphs = text.split("\n\n")
            val translatedParagraphs = mutableListOf<String>()
            var currentBatch = StringBuilder()

            for (paragraph in paragraphs) {
                if (currentBatch.length + paragraph.length + 2 > 4000 && currentBatch.isNotEmpty()) {
                    val batchResult = translateSingleBlock(currentBatch.toString(), sl, tl)
                    if (batchResult.isFailure) {
                        return@withContext batchResult
                    }
                    translatedParagraphs.add(batchResult.getOrThrow())
                    currentBatch = StringBuilder()
                }
                if (currentBatch.isNotEmpty()) currentBatch.append("\n\n")
                currentBatch.append(paragraph)
            }

            if (currentBatch.isNotEmpty()) {
                val batchResult = translateSingleBlock(currentBatch.toString(), sl, tl)
                if (batchResult.isFailure) {
                    return@withContext batchResult
                }
                translatedParagraphs.add(batchResult.getOrThrow())
            }

            return@withContext Result.success(translatedParagraphs.joinToString("\n\n"))
        } else {
            return@withContext translateSingleBlock(text, sl, tl)
        }
    }

    private fun translateSingleBlock(text: String, sl: String, tl: String): Result<String> {
        // Attempt translation via TomatoMTL's Google Translation Engine
        val primaryResult = runCatching {
            executeTomatoMtlGoogleEngine(text, sl, tl)
        }

        if (primaryResult.isSuccess && !primaryResult.getOrNull().isNullOrBlank()) {
            return Result.success(primaryResult.getOrNull()!!)
        }

        // Secondary fallback to single translation endpoint
        val secondaryResult = runCatching {
            executeFallbackEngine(text, sl, tl)
        }

        if (secondaryResult.isSuccess && !secondaryResult.getOrNull().isNullOrBlank()) {
            return Result.success(secondaryResult.getOrNull()!!)
        }

        val error = primaryResult.exceptionOrNull() ?: secondaryResult.exceptionOrNull()
        ?: IllegalStateException("Translation returned empty text from engine.")

        Log.e("TomatoMTLProvider", "Translation request failed: ${error.message}", error)
        return Result.failure(error)
    }

    /**
     * TomatoMTL's Google Translation Engine using direct POST request with form body.
     */
    private fun executeTomatoMtlGoogleEngine(text: String, sl: String, tl: String): String {
        val url = "https://translate.googleapis.com/translate_a/t?client=dict-chrome-ex&sl=$sl&tl=$tl"

        val formBody = FormBody.Builder()
            .add("q", text)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Referer", "https://tomatomtl.com/translate")
            .header("Origin", "https://tomatomtl.com")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("TomatoMTL Engine HTTP ${response.code}: ${response.message}")
            }
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty HTTP response body")
            return parseResponse(responseBody)
        }
    }

    /**
     * Fallback translation engine using single format if primary endpoint encounters issues.
     */
    private fun executeFallbackEngine(text: String, sl: String, tl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t"

        val formBody = FormBody.Builder()
            .add("q", text)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .header("Referer", "https://tomatomtl.com/translate")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Fallback HTTP ${response.code}: ${response.message}")
            }
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty HTTP response body")
            return parseResponse(responseBody)
        }
    }

    private fun parseResponse(responseBody: String): String {
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("[")) {
            val jsonArray = JSONArray(trimmed)
            val sb = StringBuilder()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.opt(i)
                if (item is String) {
                    sb.append(item)
                } else if (item is JSONArray) {
                    for (j in 0 until item.length()) {
                        val sub = item.opt(j)
                        if (sub is JSONArray && sub.length() > 0) {
                            sb.append(sub.optString(0))
                        } else if (sub is String) {
                            sb.append(sub)
                        }
                    }
                }
            }
            val res = sb.toString()
            if (res.isNotBlank()) return res
        } else if (trimmed.startsWith("{")) {
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

        if (trimmed.isNotBlank() && !trimmed.startsWith("<")) {
            return trimmed
        }

        throw IllegalStateException("Translation returned empty or invalid response")
    }
}
