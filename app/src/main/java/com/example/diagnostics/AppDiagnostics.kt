package com.example.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small on-device event log for background playback problems: refused foreground-service starts,
 * engine recoveries, process deaths and crashes. It lives in app storage (never uploaded) and can be
 * shared from Settings so a "TTS stopped at night" report comes with the actual reason.
 */
object AppDiagnostics {
    private const val TAG = "AppDiagnostics"
    private const val DIR = "diagnostics"
    private const val LOG_FILE = "events.log"
    private const val OLD_LOG_FILE = "events.old.log"
    private const val REPORT_FILE = "diagnostics-report.txt"
    private const val MAX_LOG_BYTES = 256 * 1024L
    private const val PREFS = "app_diagnostics"
    private const val KEY_LAST_EXIT_TS = "last_exit_timestamp"

    @Volatile
    private var dir: File? = null

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-log").apply { isDaemon = true }
    }

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        dir = File(context.filesDir, DIR).apply { mkdirs() }
    }

    fun log(tag: String, message: String) {
        Log.i("Diag/$tag", message)
        val target = dir ?: return
        val line = "${format(System.currentTimeMillis())} [$tag] $message\n"
        try {
            writer.execute { append(target, line) }
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostics writer rejected an entry", e)
        }
    }

    /** Writes synchronously: used from the uncaught-exception handler right before the process dies. */
    fun logCrash(thread: Thread, error: Throwable) {
        val target = dir ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        append(target, "${format(System.currentTimeMillis())} [crash] Uncaught exception on ${thread.name}: $trace\n")
    }

    /**
     * Logs why earlier processes of this app ended (Android 11+), each exit once. This is how a
     * process killed while the screen was off shows up (low memory, OEM kill, crash, ANR...).
     */
    fun recordProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            for (info in exits) {
                log(
                    "exit",
                    "Previous process ended ${format(info.timestamp)}: ${exitReasonName(info.reason)}" +
                        " (importance=${info.importance}, status=${info.status}" +
                        (info.description?.let { ", $it" } ?: "") + ")"
                )
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_LAST_EXIT_TS, it).apply() }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read process exit reasons", e)
        }
    }

    /** Builds a plain-text report (device, power state, recent events) for sharing. */
    fun buildReport(context: Context): File? {
        val target = dir ?: File(context.filesDir, DIR).apply { mkdirs() }
        flush()
        return try {
            val report = File(target, REPORT_FILE)
            report.writeText(buildString {
                appendLine("Fanqie Translate diagnostics")
                appendLine("Created: ${format(System.currentTimeMillis())}")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Battery optimization ignored: ${BackgroundPolicy.isIgnoringBatteryOptimizations(context)}")
                appendLine("Background restricted: ${BackgroundPolicy.isBackgroundRestricted(context)}")
                standbyBucket(context)?.let { appendLine("App standby bucket: $it") }
                appendLine()
                for (name in listOf(OLD_LOG_FILE, LOG_FILE)) {
                    val file = File(target, name)
                    if (file.exists()) append(file.readText())
                }
            })
            report
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't build the diagnostics report", e)
            null
        }
    }

    /** Waits until queued entries are on disk (reports and tests). */
    fun flush() {
        try {
            writer.submit {}.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private fun append(target: File, line: String) {
        try {
            val file = File(target, LOG_FILE)
            if (file.length() > MAX_LOG_BYTES) {
                val old = File(target, OLD_LOG_FILE)
                old.delete()
                file.renameTo(old)
            }
            File(target, LOG_FILE).appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write a diagnostics entry", e)
        }
    }

    private fun format(timestamp: Long): String = timeFormat.get()!!.format(Date(timestamp))

    private fun standbyBucket(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            when (val bucket = usm.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
                else -> bucket.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    internal fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_CRASH -> "crashed (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system (other)"
        ApplicationExitInfo.REASON_FREEZER -> "frozen and killed"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "unknown ($reason)"
    }
}
