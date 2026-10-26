package com.example.tomato

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tomato.engine.TomatoEngineClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Real engine against the real Fanqie servers. Needs network; run with `-e live true`.
 * Installs the pinned engine, looks a book up, searches, and downloads three chapters as EPUB.
 */
@RunWith(AndroidJUnit4::class)
class TomatoEngineLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun engineLooksUpSearchesAndDownloads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val installer = TomatoEngineInstaller(context)
        val engine = installer.installed() ?: installer.download(TomatoEngineInstaller.PINNED_RELEASE) {}.also(installer::makeCurrent)
        Log.i(TAG, "engine ${engine.tag} at ${engine.path}")
        val client = TomatoEngineClient(context) { installer.installed()?.path }

        val jobs = client.call("list_jobs")
        Log.i(TAG, "list_jobs: ${jobs.take(500)}")

        val detail = client.call("book_detail", JSONObject().put("book_id", BOOK_ID).put("include_chapters", false).toString())
        Log.i(TAG, "book_detail: ${detail.take(3000)}")
        assertTrue(detail.contains("十日终焉"))

        val search = client.call("search", JSONObject().put("q", "十日终焉").toString())
        Log.i(TAG, "search: ${search.take(1500)}")
        assertTrue(JSONObject(search).optJSONArray("items")!!.length() > 0)

        val saveDir = File(context.filesDir, "tomato/live_test").apply { deleteRecursively(); mkdirs() }
        val created = client.call(
            "create_download",
            JSONObject()
                .put("book_id", BOOK_ID)
                .put("book_name", "十日终焉")
                .put("author", "杀虫队队员")
                .put("book_input", BOOK_ID)
                .put("save_dir", saveDir.absolutePath)
                .put("file_format", "epub")
                .put("overwrite_existing", true)
                .put("chapter_start", 1)
                .put("chapter_end", 3)
                .toString()
        )
        Log.i(TAG, "create_download: ${created.take(2000)}")

        var job: JSONObject? = null
        for (attempt in 0 until 180) {
            val items = JSONObject(client.call("list_jobs")).optJSONArray("items") ?: JSONArray()
            job = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("book_id") == BOOK_ID }
            Log.i(TAG, "job: ${job?.toString()?.take(800)}")
            val status = job?.optString("status").orEmpty()
            if (status == "done" || status == "failed" || status == "canceled" || status == "cancelled") break
            delay(1_000)
        }
        assertEquals("done", job?.optString("status"))
        val output = File(job!!.optString("output_path"))
        Log.i(TAG, "output ${output.absolutePath} ${output.length()} bytes; dir=${saveDir.listFiles()?.map { it.name }}")
        assertTrue(output.isFile && output.length() > 1_000)
        ZipFile(output).use { zip -> assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).bufferedReader().readText().trim()) }
    }

    private companion object {
        const val TAG = "TomatoLive"
        const val BOOK_ID = "7143038691944959011"
    }
}
