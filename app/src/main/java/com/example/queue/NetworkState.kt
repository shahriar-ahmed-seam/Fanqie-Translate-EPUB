package com.example.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Whether the phone can reach the internet right now. */
object NetworkState {
    fun isOnline(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    } catch (e: Exception) {
        // Unknown: assume online; translation requests fail on their own if not.
        true
    }
}
