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

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val isNewer: Boolean
)

class AppUpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(owner: String, repo: String): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "EPUB-Translator-Android")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@runCatching null
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub API returned ${response.code}: ${response.message}")
                }
                val body = response.body?.string() ?: return@runCatching null
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "")
                val versionName = normalizeVersion(tagName)
                val releaseNotes = json.optString("body", "No release notes provided.")

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val isNewer = isVersionNewer(versionName, currentVersion)

                ReleaseInfo(
                    tagName = tagName,
                    versionName = versionName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkDownloadUrl,
                    isNewer = isNewer
                )
            }
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
