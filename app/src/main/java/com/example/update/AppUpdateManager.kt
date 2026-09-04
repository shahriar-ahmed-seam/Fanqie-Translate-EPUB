package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val isNewer: Boolean
)

class AppUpdateManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    private var lastCheckTimeMs: Long = 0L
    @Volatile
    private var lastCheckKey: String = ""
    @Volatile
    private var cachedReleaseInfo: ReleaseInfo? = null
    private val cacheLock = Any()

    suspend fun checkForUpdates(
        owner: String,
        repo: String,
        force: Boolean = false
    ): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        val cleanOwner = sanitizeOwner(owner)
        val cleanRepo = sanitizeRepo(repo)
        val cacheKey = "$cleanOwner/$cleanRepo"

        synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            // Throttle: cache valid for 30s unless force is explicitly requested
            if (!force && cacheKey == lastCheckKey && (now - lastCheckTimeMs) < 30_000L) {
                return@withContext Result.success(cachedReleaseInfo)
            }
        }

        try {
            val url = "https://api.github.com/repos/$cleanOwner/$cleanRepo/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "EPUB-Translator-Android")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = runCatching { response.body?.string() }.getOrNull()
                    val errJson = errBody?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val apiMessage = errJson?.optString("message", "")?.trim() ?: ""

                    val diagnosticMsg = when (response.code) {
                        403 -> {
                            val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                            if (remaining == 0 || apiMessage.contains("rate limit", ignoreCase = true)) {
                                "GitHub API rate limit exceeded (HTTP 403). Please try again later."
                            } else if (apiMessage.isNotBlank()) {
                                "GitHub API forbidden (HTTP 403): $apiMessage"
                            } else {
                                "GitHub API access forbidden (HTTP 403)."
                            }
                        }
                        404 -> {
                            "No published releases found for repository '$cleanOwner/$cleanRepo' (HTTP 404)."
                        }
                        429 -> {
                            "Too many requests to GitHub API (HTTP 429). Please wait before checking again."
                        }
                        in 500..599 -> {
                            "GitHub servers are currently unavailable (HTTP ${response.code}). Please try again later."
                        }
                        else -> {
                            if (apiMessage.isNotBlank()) {
                                "GitHub API error (${response.code}): $apiMessage"
                            } else {
                                "GitHub API returned HTTP ${response.code}."
                            }
                        }
                    }
                    return@withContext Result.failure(IOException(diagnosticMsg))
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure(IOException("GitHub API returned an empty response body."))
                }

                val json = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    return@withContext Result.failure(IOException("Failed to parse GitHub release data: ${e.message}", e))
                }

                val tagName = json.optString("tag_name", "").trim()
                if (tagName.isBlank()) {
                    return@withContext Result.failure(IOException("Latest release does not contain a valid tag_name."))
                }

                val versionName = normalizeVersion(tagName)
                val releaseNotes = json.optString("body", "No release notes provided.")

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val downloadUrl = asset.optString("browser_download_url", "").trim()
                            if (downloadUrl.isNotBlank()) {
                                apkDownloadUrl = downloadUrl
                                break
                            }
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val isNewer = isVersionNewer(versionName, currentVersion)

                val releaseInfo = ReleaseInfo(
                    tagName = tagName,
                    versionName = versionName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkDownloadUrl,
                    isNewer = isNewer
                )

                synchronized(cacheLock) {
                    lastCheckTimeMs = System.currentTimeMillis()
                    lastCheckKey = cacheKey
                    cachedReleaseInfo = releaseInfo
                }

                Result.success(releaseInfo)
            }
        } catch (e: UnknownHostException) {
            Result.failure(IOException("Unable to reach GitHub. Please check your internet connection or DNS.", e))
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("Connection to GitHub timed out. Please check your network.", e))
        } catch (e: SSLException) {
            Result.failure(IOException("Secure connection (SSL/TLS) to GitHub failed: ${e.localizedMessage ?: e.message}", e))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IOException(e.localizedMessage ?: e.message ?: "Unexpected error checking for updates", e))
        }
    }

    suspend fun downloadAndInstallApk(downloadUrl: String, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: ${response.code}")
                val body = response.body ?: throw IllegalStateException("Response body is null")
                val totalLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded: Long = 0
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                onProgress(downloaded.toFloat() / totalLength.toFloat())
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Launch package installer via FileProvider
            withContext(Dispatchers.Main) {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            }
        }
    }

    companion object {
        fun sanitizeOwner(rawOwner: String): String {
            return rawOwner.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .substringBefore("/")
                .trim()
                .ifBlank { "shahriar-ahmed-seam" }
                .let { if (it.equals("shahriarseam", ignoreCase = true)) "shahriar-ahmed-seam" else it }
        }

        fun sanitizeRepo(rawRepo: String): String {
            return rawRepo.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .substringAfterLast("/")
                .removeSuffix(".git")
                .trim()
                .ifBlank { "Fanqie-Translate-EPUB" }
                .let { if (it.equals("epub-translator", ignoreCase = true)) "Fanqie-Translate-EPUB" else it }
        }

        fun normalizeVersion(raw: String): String {
            return raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .trim()
                .substringBefore("-")
                .substringBefore("+")
                .trim()
        }

        fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
            val cleanRemote = normalizeVersion(remoteVersion)
            val cleanCurrent = normalizeVersion(currentVersion)
            if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false

            val remoteParts = cleanRemote.split(".").map { part ->
                part.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
            val currentParts = cleanCurrent.split(".").map { part ->
                part.filter { it.isDigit() }.toIntOrNull() ?: 0
            }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
