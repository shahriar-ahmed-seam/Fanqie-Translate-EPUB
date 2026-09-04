package com.example.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppUpdateManagerTest {

    @Test
    fun testNormalizeVersion() {
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("v1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("V1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("  v1.0.3  "))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("v1.0.3-beta.1"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("1.0.3+20260829"))
        assertEquals("1.0.6", AppUpdateManager.normalizeVersion("v1.0.6-rc2+build.42"))
        assertEquals("", AppUpdateManager.normalizeVersion(""))
        assertEquals("", AppUpdateManager.normalizeVersion("   "))
    }

    @Test
    fun testSanitizeOwnerAndRepo() {
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner("shahriar-ahmed-seam"))
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner("  shahriar-ahmed-seam  "))
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner("https://github.com/shahriar-ahmed-seam"))
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner("github.com/shahriar-ahmed-seam/"))
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner(""))
        assertEquals("shahriar-ahmed-seam", AppUpdateManager.sanitizeOwner("shahriarseam"))

        assertEquals("Fanqie-Translate-EPUB", AppUpdateManager.sanitizeRepo("Fanqie-Translate-EPUB"))
        assertEquals("Fanqie-Translate-EPUB", AppUpdateManager.sanitizeRepo("  Fanqie-Translate-EPUB  "))
        assertEquals("Fanqie-Translate-EPUB", AppUpdateManager.sanitizeRepo("https://github.com/shahriar-ahmed-seam/Fanqie-Translate-EPUB.git"))
        assertEquals("Fanqie-Translate-EPUB", AppUpdateManager.sanitizeRepo(""))
        assertEquals("Fanqie-Translate-EPUB", AppUpdateManager.sanitizeRepo("epub-translator"))
    }

    @Test
    fun testSameVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.6", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.6", "v1.0.6"))
    }

    @Test
    fun testOlderVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("0.9.9", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.5", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.6"))
    }

    @Test
    fun testNewerPatchVersionIsNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.5"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.10", "1.0.9"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.10", "1.0.2"))
    }

    @Test
    fun testNewerMinorAndMajorVersion() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.1.0", "1.0.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.0.6"))
    }

    @Test
    fun testEdgeCases() {
        assertFalse(AppUpdateManager.isVersionNewer("", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", ""))
        assertFalse(AppUpdateManager.isVersionNewer("  ", "  "))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.6-beta.1", "1.0.5"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.5-beta.1", "1.0.5"))
    }

    @Test
    fun testSuccessfulReleaseCheckAndApkAssetExtraction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jsonPayload = """
            {
              "tag_name": "v9.9.9",
              "body": "Major release features",
              "assets": [
                {
                  "name": "unrelated_checksum.txt",
                  "browser_download_url": "https://github.com/checksum.txt"
                },
                {
                  "name": "EPUB-Translator-v9.9.9.apk",
                  "browser_download_url": "https://github.com/releases/download/v9.9.9/app.apk"
                }
              ]
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val manager = AppUpdateManager(context, mockClient)
        val result = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = true)

        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertEquals("v9.9.9", info?.tagName)
        assertEquals("9.9.9", info?.versionName)
        assertEquals("Major release features", info?.releaseNotes)
        assertEquals("https://github.com/releases/download/v9.9.9/app.apk", info?.apkDownloadUrl)
        assertTrue(info?.isNewer == true)
    }

    @Test
    fun testDiagnosticMessageFor403RateLimit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("X-RateLimit-Remaining", "0")
                    .body("""{"message": "API rate limit exceeded for 1.2.3.4"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val manager = AppUpdateManager(context, mockClient)
        val result = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = true)

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Message should explain rate limit: $msg", msg.contains("rate limit exceeded (HTTP 403)"))
    }

    @Test
    fun testDiagnosticMessageFor404NotFound() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("""{"message": "Not Found"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val manager = AppUpdateManager(context, mockClient)
        val result = manager.checkForUpdates("some-user", "non-existent-repo", force = true)

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Message should explain 404: $msg", msg.contains("No published releases found for repository") && msg.contains("HTTP 404"))
    }

    @Test
    fun testDiagnosticMessageFor429TooManyRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val manager = AppUpdateManager(context, mockClient)
        val result = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = true)

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Message should explain 429: $msg", msg.contains("Too many requests to GitHub API (HTTP 429)"))
    }

    @Test
    fun testThrottlingPreventsUnnecessaryRepeatedCalls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkCallsCount = AtomicInteger(0)
        val jsonPayload = """
            {
              "tag_name": "v1.0.0",
              "body": "Initial",
              "assets": []
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCallsCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val manager = AppUpdateManager(context, mockClient)

        // First call should hit network
        val result1 = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = false)
        assertTrue(result1.isSuccess)
        assertEquals(1, networkCallsCount.get())

        // Immediate second unforced call should use cache, network count remains 1
        val result2 = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = false)
        assertTrue(result2.isSuccess)
        assertEquals(1, networkCallsCount.get())

        // Forced call should hit network again, network count increments to 2
        val result3 = manager.checkForUpdates("shahriar-ahmed-seam", "Fanqie-Translate-EPUB", force = true)
        assertTrue(result3.isSuccess)
        assertEquals(2, networkCallsCount.get())
    }
}
