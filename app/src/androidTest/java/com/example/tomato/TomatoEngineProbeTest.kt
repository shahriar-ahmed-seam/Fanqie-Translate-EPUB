package com.example.tomato

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tomato.engine.TomatoEngineClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual probe of the engine's job control (run with `-e probe true`): lists the engine's jobs and,
 * with `-e resume true`, resumes paused ones and logs their progress for a while.
 */
@RunWith(AndroidJUnit4::class)
class TomatoEngineProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun probeJobs() = runBlocking<Unit> {
        assumeTrue(args.getString("probe") == "true")
        val installer = TomatoEngineInstaller(context)
        val client = TomatoEngineClient(context) { installer.installed()?.path }
        val jobs = jobs(client)
        jobs.forEach { Log.i(TAG, "job ${it.optString("id")} ${it.optString("status")} ${it.opt("progress_done")}/${it.opt("progress_total")} ${it.optString("message")}") }
        if (args.getString("resume") != "true") return@runBlocking
        for (job in jobs.filter { it.optString("status") == "paused" }) {
            val reply = runCatching { client.call("resume_job", JSONObject().put("job_id", job.optString("id")).toString(), 30_000L) }
            Log.i(TAG, "resume_job ${job.optString("id")} -> ${reply.getOrNull()?.take(600) ?: reply.exceptionOrNull()}")
        }
        repeat(15) {
            delay(2_000)
            jobs(client).take(3).forEach { Log.i(TAG, "after resume: ${it.optString("id")} ${it.optString("status")} ${it.opt("progress_done")}/${it.opt("progress_total")} ${it.optString("message")}") }
        }
    }

    private suspend fun jobs(client: TomatoEngineClient): List<JSONObject> {
        val items = JSONObject(client.call("list_jobs", "{}", 60_000L)).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    private companion object {
        const val TAG = "TomatoProbe"
    }
}
