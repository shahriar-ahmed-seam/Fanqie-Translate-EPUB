package com.example.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class TomatoMTLProvider(
    private val timeoutSeconds: Long = 30L,
    maxConcurrentRequests: Int = 64
) : TranslationProvider {

    override val name: String = "TomatoMTL (Google Engine)"

    companion object {
        private const val TAG = "TomatoMTLProvider"

        /** Longest text sent in one request; the chunk size setting can't exceed it. */
        const val MAX_REQUEST_CHARS = 4200

        /** Pause for every worker after Google answered with nothing usable (it does that when overloaded). */
        private const val SOFT_FAILURE_COOLDOWN_MS = 2_000L
        /** Pause after Google answered with a web page (its "unusual traffic" check) instead of JSON. */
        private const val BLOCKED_PAGE_COOLDOWN_MS = 20_000L
        /** Requests at least this long pause every worker when the answer is unusable. */
        private const val SHARED_COOLDOWN_MIN_CHARS = 600

        @Volatile
        private var rateLimitUntil = 0L

        fun setRateLimitCooldown(cooldownMs: Long) {
            val target = System.currentTimeMillis() + cooldownMs
            if (target > rateLimitUntil) {
                rateLimitUntil = target
            }
        }

        /**
         * Text of a `translate_a/t` answer: `["text"]`, or `[["text","zh-CN"]]` when Google detected the
         * language. An empty translation is `[""]`; it returns "" and never the raw body (that is how
         * `[""]` ended up stored as whole chapters).
         */
        internal fun parseTEndpoint(body: String): String {
            val root = JSONArray(body)
            val sb = StringBuilder()
            for (i in 0 until root.length()) {
                when (val item = root.opt(i)) {
                    is String -> sb.append(item)
                    is JSONArray -> (item.opt(0) as? String)?.let { sb.append(it) }
                }
            }
            return sb.toString()
        }

        /**
         * Text of a `translate_a/single` answer: only the translated pieces of `d[0]` (`d[0][i][0]`).
         * The rest of the answer holds the detected language and confidence values, which the old
         * parser glued to the text ("Chapter 1 Empty Housezh-CN").
         */
        internal fun parseSingleEndpoint(body: String): String {
            val root = JSONArray(body)
            val segments = root.opt(0) as? JSONArray ?: return ""
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.opt(i) as? JSONArray ?: continue
                (segment.opt(0) as? String)?.let { sb.append(it) }
            }
            return sb.toString()
        }
    }

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

        // Shared rate limit throttle: if a recent 429 occurred, wait for the cooldown window
        val waitMs = rateLimitUntil - System.currentTimeMillis()
        if (waitMs > 0) {
            delay(waitMs.coerceAtMost(15000L))
        }

        val sl = if (sourceLang.startsWith("zh")) "zh-CN" else sourceLang
        val tl = if (targetLang.isBlank()) "en" else targetLang

        // If chunk is unexpectedly large (> 4200 chars), split by paragraph boundaries safely
        if (text.length > MAX_REQUEST_CHARS) {
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
        // Nothing Chinese in it (numbers, symbols, English): keep it as it is.
        if (!TranslationQuality.needsTranslation(text)) return Result.success(text)

        // Attempt translation via TomatoMTL's Google Translation Engine
        val primaryResult = runCatching {
            executeTomatoMtlGoogleEngine(text, sl, tl)
        }
        val primaryText = primaryResult.getOrNull()
        if (primaryText != null && TranslationQuality.looksTranslated(text, primaryText)) {
            return Result.success(TranslationQuality.usable(primaryText, text) ?: text)
        }

        val primaryError = primaryResult.exceptionOrNull()
        // If primary engine hit 429 rate limit, 403 blocked or a web page, do not immediately hammer fallback engine
        if (primaryError is TranslationException && (primaryError.isRateLimited || primaryError.isPermanent || primaryError.retryAfterMs != null)) {
            Log.w(TAG, "Primary engine non-recoverable status: ${primaryError.message}")
            return Result.failure(primaryError)
        }

        // Secondary fallback to single translation endpoint (also used when the primary answer was
        // empty or still Chinese)
        val secondaryResult = runCatching {
            executeFallbackEngine(text, sl, tl)
        }
        val secondaryText = secondaryResult.getOrNull()
        if (secondaryText != null && TranslationQuality.looksTranslated(text, secondaryText)) {
            return Result.success(TranslationQuality.usable(secondaryText, text) ?: text)
        }

        // Neither answer is clean, but one is a real (partial) translation: return the one with the
        // least Chinese left; the queue re-translates the paragraphs that are still Chinese.
        val bestEffort = listOfNotNull(primaryText, secondaryText)
            .mapNotNull { TranslationQuality.usable(it, text) }
            .filterNot { TranslationQuality.isEcho(text, it) }
            .minByOrNull { TranslationQuality.hanCount(it) }
        if (bestEffort != null && TranslationQuality.hanCount(bestEffort) < TranslationQuality.hanCount(text)) {
            return Result.success(bestEffort)
        }

        val secondaryError = secondaryResult.exceptionOrNull()
        val answeredBadly = primaryText != null || secondaryText != null ||
            (primaryError as? TranslationException)?.isSoftFailure == true ||
            (secondaryError as? TranslationException)?.isSoftFailure == true
        val error = when {
            secondaryError is TranslationException && secondaryError.isRateLimited -> secondaryError
            answeredBadly -> {
                // Google answered, but with nothing usable: it does this when overloaded. For whole
                // chunks everyone slows down a little (single lines and words don't pause the queue)
                // and the chunk is retried later.
                if (text.length >= SHARED_COOLDOWN_MIN_CHARS) setRateLimitCooldown(SOFT_FAILURE_COOLDOWN_MS)
                TranslationException(
                    "Google returned an empty or untranslated answer",
                    isSoftFailure = true
                )
            }
            else -> secondaryError ?: primaryError
                ?: TranslationException("Translation returned empty text from engine.", isSoftFailure = true)
        }

        Log.w(TAG, "Translation request failed: ${error.message}")
        return Result.failure(error)
    }

    /**
     * TomatoMTL's Google Translation Engine using direct POST request with form body.
     */
    internal fun executeTomatoMtlGoogleEngine(text: String, sl: String, tl: String): String {
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

        return executeRequestAndParse(request, "TomatoMTL Engine", ::parseTEndpoint)
    }

    /**
     * Fallback translation engine using single format if primary endpoint encounters issues.
     */
    internal fun executeFallbackEngine(text: String, sl: String, tl: String): String {
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

        return executeRequestAndParse(request, "Fallback Engine", ::parseSingleEndpoint)
    }

    private fun executeRequestAndParse(request: Request, engineName: String, parseArray: (String) -> String): String {
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code

                if (code == 429) {
                    val retryAfterHeader = response.header("Retry-After")
                    val retryAfterSec = retryAfterHeader?.toLongOrNull() ?: 3L
                    val retryAfterMs = (retryAfterSec.coerceIn(1, 60)) * 1000L
                    setRateLimitCooldown(retryAfterMs)
                    throw TranslationException(
                        message = "$engineName HTTP 429: Too Many Requests (Rate Limited)",
                        statusCode = 429,
                        isRateLimited = true,
                        retryAfterMs = retryAfterMs
                    )
                }

                if (code == 403) {
                    throw TranslationException(
                        message = "$engineName HTTP 403: Forbidden (Request Blocked)",
                        statusCode = 403,
                        isPermanent = true
                    )
                }

                if (code == 408) {
                    throw TranslationException(
                        message = "$engineName HTTP 408: Request Timeout",
                        statusCode = 408
                    )
                }

                if (code in 500..599) {
                    throw TranslationException(
                        message = "$engineName HTTP $code: Server Error (${response.message})",
                        statusCode = code
                    )
                }

                if (!response.isSuccessful) {
                    val isPermanent = code in 400..499 && code != 408 && code != 429
                    throw TranslationException(
                        message = "$engineName HTTP $code: ${response.message}",
                        statusCode = code,
                        isPermanent = isPermanent
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    throw TranslationException("Empty HTTP response body from $engineName", statusCode = code, isSoftFailure = true)
                }

                return parseResponse(responseBody, engineName, parseArray)
            }
        } catch (e: TranslationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw TranslationException("Socket timeout: ${e.message}", statusCode = 408, cause = e)
        } catch (e: UnknownHostException) {
            throw TranslationException("DNS failure (cannot resolve host): ${e.message}", cause = e)
        } catch (e: ConnectException) {
            throw TranslationException("Connection failure: ${e.message}", cause = e)
        } catch (e: IOException) {
            throw TranslationException("Network I/O error: ${e.message}", cause = e)
        }
    }

    /**
     * The translated text of an answer. Anything that isn't a well-formed answer is a failure: the
     * raw body is never returned as if it were a translation.
     */
    private fun parseResponse(responseBody: String, engineName: String, parseArray: (String) -> String): String {
        val trimmed = responseBody.trim()

        if (trimmed.startsWith("<")) {
            // A web page instead of JSON (Google's "unusual traffic" check, or a network login page).
            // Everyone pauses, and the chunk tries again after a long wait. It counts as a failed try,
            // so a page that never goes away fails the chunk instead of retrying forever.
            setRateLimitCooldown(BLOCKED_PAGE_COOLDOWN_MS)
            throw TranslationException(
                message = "$engineName answered with a web page instead of a translation",
                retryAfterMs = BLOCKED_PAGE_COOLDOWN_MS
            )
        }

        val text = try {
            when {
                trimmed.startsWith("[") -> parseArray(trimmed)
                trimmed.startsWith("{") -> {
                    val json = JSONObject(trimmed)
                    when {
                        json.has("translatedText") -> json.optString("translatedText")
                        json.has("translation") -> json.optString("translation")
                        else -> ""
                    }
                }
                else -> ""
            }
        } catch (e: org.json.JSONException) {
            throw TranslationException("$engineName sent an unreadable answer", isSoftFailure = true, cause = e)
        }

        val result = text.trim()
        if (result.isEmpty()) {
            throw TranslationException("$engineName returned an empty translation", isSoftFailure = true)
        }
        return result
    }
}

