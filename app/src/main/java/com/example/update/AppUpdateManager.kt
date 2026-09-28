package com.example.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val isNewer: Boolean,
    /** Asset size in bytes from the release (0 when unknown). */
    val apkSizeBytes: Long = 0L,
    /** Lowercase hex SHA-256 GitHub publishes for the asset ("digest"), when present. */
    val apkSha256: String? = null
)

/** Why a downloaded APK can't be installed over this app. */
class UpdateVerificationException(message: String) : IOException(message)

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
                var apkSize = 0L
                var apkSha256: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val downloadUrl = asset.optString("browser_download_url", "").trim()
                            if (downloadUrl.isNotBlank()) {
                                apkDownloadUrl = downloadUrl
                                apkSize = asset.optLong("size", 0L)
                                apkSha256 = parseSha256Digest(asset.optString("digest", ""))
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
                    isNewer = isNewer,
                    apkSizeBytes = apkSize,
                    apkSha256 = apkSha256
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

    /**
     * Downloads the release APK and checks it before anything is installed: complete (size and
     * GitHub's SHA-256 when published), same package, newer version, and signed with the same key as
     * the installed app (otherwise Android refuses with a vague "App not installed").
     * [onProgress] gets 0..1, or null while the size is unknown.
     */
    suspend fun downloadUpdate(info: ReleaseInfo, onProgress: (Float?) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = info.apkDownloadUrl ?: throw IOException("This release has no APK to download.")
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updateDir.listFiles()?.forEach { it.delete() } // old downloads
            val safeTag = info.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val partFile = File(updateDir, "update-$safeTag.apk.part")
            val apkFile = File(updateDir, "update-$safeTag.apk")

            val request = Request.Builder().url(url).header("User-Agent", "EPUB-Translator-Android").get().build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("Download failed: empty response.")
                val totalLength = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes.takeIf { it > 0 } ?: -1L
                onProgress(if (totalLength > 0) 0f else null)

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (totalLength > 0) {
                                val percent = ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress((downloaded.toFloat() / totalLength).coerceIn(0f, 1f))
                                }
                            }
                        }
                        output.flush()
                        output.fd.sync()
                        if (info.apkSizeBytes > 0 && downloaded != info.apkSizeBytes) {
                            throw IOException("Download incomplete ($downloaded of ${info.apkSizeBytes} bytes). Please try again.")
                        }
                    }
                }
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.apkSha256 != null && !info.apkSha256.equals(sha256, ignoreCase = true)) {
                partFile.delete()
                throw IOException("The downloaded file is damaged (checksum mismatch). Please try again.")
            }
            if (!partFile.renameTo(apkFile)) throw IOException("Couldn't save the downloaded update.")

            verifyApk(apkFile)?.let { problem ->
                apkFile.delete()
                throw UpdateVerificationException(problem)
            }
            apkFile
        }
    }

    /** Returns a user-facing reason the APK can't update this app, or null when it can. */
    fun verifyApk(apk: File): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.absolutePath)
            ?: return "The downloaded file isn't a valid app package. Please try again."
        if (archive.packageName != context.packageName) {
            return "The downloaded app is a different app (${archive.packageName})."
        }
        val installed = try {
            installedInfo(pm)
        } catch (e: Exception) {
            null
        }
        if (installed != null && versionCodeOf(archive) <= versionCodeOf(installed)) {
            return "The downloaded version isn't newer than the installed one."
        }
        val archiveCerts = signingCertDigests(archive)
        val installedCerts = installed?.let { signingCertDigests(it) }.orEmpty()
        if (archiveCerts.isEmpty()) return "The downloaded app isn't signed."
        if (installedCerts.isNotEmpty() && archiveCerts.intersect(installedCerts).isEmpty()) {
            return "This update is signed with a different key than the installed app (very old builds used a " +
                "test key). Uninstall this version, then install the new APK from GitHub. Your library " +
                "will be removed by the uninstall."
        }
        return null
    }

    /** Android 8+ needs the user to allow installs from this app ("Install unknown apps"). */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens this app's "Install unknown apps" switch. */
    fun openInstallPermissionSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the install permission screen", e)
            false
        }
    }

    /**
     * Hands the verified APK to the system installer. Launched from the visible activity: Android 10+
     * silently blocks activity starts from the background, which left the old flow stuck at 100%.
     */
    fun launchInstaller(activity: Activity, apk: File): Boolean = try {
        val apkUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't launch the package installer", e)
        false
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(path, flags) ?: return null
        info.applicationInfo?.let {
            it.sourceDir = path
            it.publicSourceDir = path
        }
        return info
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageInfo(context.packageName, flags)
    }

    companion object {
        private const val TAG = "AppUpdateManager"

        /** Public repository that only holds release APKs (the source repository is private). */
        const val DEFAULT_OWNER = "shahriar-ahmed-seam"
        const val DEFAULT_REPO = "Fanqie-Translate-EPUB-releases"

        /** Repository names older versions used; their releases API is unreachable (private or gone). */
        private val LEGACY_REPOS = setOf("epub-translator", "fanqie-translate-epub")

        fun sanitizeOwner(rawOwner: String): String {
            return rawOwner.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .substringBefore("/")
                .trim()
                .ifBlank { DEFAULT_OWNER }
                .let { if (it.equals("shahriarseam", ignoreCase = true)) DEFAULT_OWNER else it }
        }

        fun sanitizeRepo(rawRepo: String): String {
            return rawRepo.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .substringAfterLast("/")
                .removeSuffix(".git")
                .trim()
                .ifBlank { DEFAULT_REPO }
                .let { if (it.lowercase() in LEGACY_REPOS) DEFAULT_REPO else it }
        }

        /** GitHub reports asset digests as "sha256:<hex>". */
        fun parseSha256Digest(raw: String): String? {
            val value = raw.trim()
            if (!value.startsWith("sha256:", ignoreCase = true)) return null
            val hex = value.substringAfter(':').trim().lowercase()
            return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }

        @Suppress("DEPRECATION")
        fun versionCodeOf(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

        @Suppress("DEPRECATION")
        fun signingCertDigests(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo ?: return emptySet()
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            } else {
                info.signatures
            } ?: return emptySet()
            return signatures.map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
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
