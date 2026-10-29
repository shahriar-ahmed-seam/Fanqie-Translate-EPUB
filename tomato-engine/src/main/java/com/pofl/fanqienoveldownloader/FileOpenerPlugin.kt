package com.pofl.fanqienoveldownloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

/**
 * Stand-in for the downloader app's own Android plugin. The engine registers a plugin with this
 * class name at startup, so the class has to exist; Fanqie Translate only uses the engine for book
 * lookups and downloads into its private storage, so the file pickers, sharing, launcher and APK
 * install commands of the original app are answered with a clear "not available" instead.
 *
 * Written for Fanqie Translate (it does not reuse the original app's code).
 */
@TauriPlugin
class FileOpenerPlugin(private val activity: Activity) : Plugin(activity) {

    @Command
    fun getMobileStatus(invoke: Invoke) {
        val status = JSObject()
        status.put("batteryPercent", batteryPercent())
        status.put("charging", isCharging())
        status.put("networkType", networkType())
        status.put("navigationBarHeight", 0.0)
        invoke.resolve(status)
    }

    @Command
    fun setSystemBarsImmersive(invoke: Invoke) {
        val result = JSObject()
        result.put("enabled", false)
        result.put("keep_navigation_bar", true)
        result.put("hide_status_bar", false)
        invoke.resolve(result)
    }

    @Command
    fun getLauncherDisguise(invoke: Invoke) {
        val result = JSObject()
        result.put("id", "default")
        result.put("realEnabled", true)
        invoke.resolve(result)
    }

    @Command
    fun setLauncherDisguise(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun setTaskDescriptionMode(invoke: Invoke) = resolveEmptyLabel(invoke)

    @Command
    fun clearTaskDescriptionMode(invoke: Invoke) = resolveEmptyLabel(invoke)

    @Command
    fun canInstallPackages(invoke: Invoke) {
        val result = JSObject()
        result.put("allowed", false)
        invoke.resolve(result)
    }

    @Command
    fun requestInstallPermission(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun installApk(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun openFile(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun openUri(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun pickDirectory(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun watchDirectoryPicker(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun exportFile(invoke: Invoke) = notAvailable(invoke)

    @Command
    fun exportFileExists(invoke: Invoke) {
        val result = JSObject()
        result.put("exists", false)
        invoke.resolve(result)
    }

    @Command
    fun contentUriExists(invoke: Invoke) {
        val result = JSObject()
        result.put("exists", false)
        invoke.resolve(result)
    }

    @Command
    fun deleteContentUri(invoke: Invoke) = notAvailable(invoke)

    // Reader display settings of the original app (engine builds from 2026-10-29 on). The engine's
    // reader isn't shown inside Fanqie Translate, so they only report that nothing changed.

    @Command
    fun setReaderFullscreen(invoke: Invoke) = resolveSetting(invoke, "native", false)

    @Command
    fun setReaderKeepScreenOn(invoke: Invoke) = resolveSetting(invoke, "native", false)

    @Command
    fun setReaderVolumeKeys(invoke: Invoke) = resolveSetting(invoke, "supported", false)

    @Command
    fun setScreenOrientation(invoke: Invoke) {
        val result = JSObject()
        result.put("mode", "auto")
        result.put("fullscreen", false)
        invoke.resolve(result)
    }

    private fun resolveSetting(invoke: Invoke, key: String, value: Boolean) {
        val result = JSObject()
        result.put("enabled", false)
        result.put(key, value)
        invoke.resolve(result)
    }

    private fun resolveEmptyLabel(invoke: Invoke) {
        val result = JSObject()
        result.put("label", "")
        result.put("icon", "")
        invoke.resolve(result)
    }

    private fun notAvailable(invoke: Invoke) {
        invoke.reject("Not available inside Fanqie Translate")
    }

    private fun batteryPercent(): Int = try {
        val manager = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
    } catch (e: Exception) {
        100
    }

    private fun isCharging(): Boolean = try {
        val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) {
        false
    }

    private fun networkType(): String = try {
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> "none"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } else {
            "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }
}
