package com.example.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val payload = "definitely not an android package".toByteArray()

    private fun client(bytes: ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
            .build()
    }.build()

    private fun info(size: Long = payload.size.toLong(), sha: String? = null) = ReleaseInfo(
        tagName = "v9.9.9",
        versionName = "9.9.9",
        releaseNotes = "",
        apkDownloadUrl = "https://example.invalid/app.apk",
        isNewer = true,
        apkSizeBytes = size,
        apkSha256 = sha
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun corruptDownloadIsRejectedByChecksum() = runBlocking {
        val manager = AppUpdateManager(context, client(payload))
        val result = manager.downloadUpdate(info(sha = "0".repeat(64))) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("checksum"))
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test
    fun truncatedDownloadIsRejected() = runBlocking {
        val manager = AppUpdateManager(context, client(payload))
        val result = manager.downloadUpdate(info(size = payload.size + 100L)) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("incomplete"))
    }

    @Test
    fun fileThatIsNotAnApkNeverReachesTheInstaller() = runBlocking {
        val manager = AppUpdateManager(context, client(payload))
        val progress = mutableListOf<Float?>()
        val result = manager.downloadUpdate(info(sha = sha256(payload))) { progress.add(it) }
        assertTrue(result.exceptionOrNull() is UpdateVerificationException)
        assertEquals(1f, progress.last())
    }

    @Test
    fun releaseAssetDigestIsParsed() {
        assertEquals("ab".repeat(32), AppUpdateManager.parseSha256Digest("sha256:" + "AB".repeat(32)))
        assertNull(AppUpdateManager.parseSha256Digest(""))
    }
}
