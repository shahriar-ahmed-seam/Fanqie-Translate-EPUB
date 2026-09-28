package com.example.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What Android currently lets the app do in the background.
 *
 * Unrestricted battery use ("ignore battery optimizations") matters for audiobook playback twice: Doze
 * and OEM battery savers leave the app alone, and Android 12+ lets it start foreground services from
 * the background (needed to resume after the system restarts the playback service).
 */
object BackgroundPolicy {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** True when the user set "Restricted" battery use for the app (Android 9+). */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return try {
            am.isBackgroundRestricted
        } catch (e: Exception) {
            false
        }
    }

    /** Android 12+ refuses foreground-service starts from the background unless the app is exempt. */
    fun canStartForegroundServiceFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isIgnoringBatteryOptimizations(context)

    /**
     * Opens the system prompt that exempts the app from battery optimizations, falling back to the
     * list screen (some OEM builds don't implement the direct prompt).
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
        for (intent in listOf(direct, list, details)) {
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try the next screen.
            }
        }
        return false
    }

    /** App info screen, where "Battery" > "Unrestricted" lives on most phones. */
    fun openAppDetails(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
