package com.example.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.example.diagnostics.AppDiagnostics
import com.example.diagnostics.BackgroundPolicy

/** Screen-off listening: battery exemption status and a shareable diagnostics log. */
@Composable
fun BackgroundPlaybackCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(BackgroundPolicy.isIgnoringBatteryOptimizations(context)) }
    var restricted by remember { mutableStateOf(BackgroundPolicy.isBackgroundRestricted(context)) }

    // Re-check when returning from the system battery screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = BackgroundPolicy.isIgnoringBatteryOptimizations(context)
                restricted = BackgroundPolicy.isBackgroundRestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = modifier.fillMaxWidth().testTag("settings_background_playback"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Background listening", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            val status = when {
                restricted -> "Battery use is set to Restricted: Android will stop playback when the screen is off."
                unrestricted -> "Unrestricted battery use: playback can keep running with the screen off."
                else -> "Battery optimization is on: Android may stop playback when the phone is locked or idle."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!unrestricted || restricted) {
                FilledTonalButton(
                    onClick = {
                        if (restricted) BackgroundPolicy.openAppDetails(context)
                        else BackgroundPolicy.requestIgnoreBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_allow_background")
                ) { Text("Allow background playback") }
            }
            Text(
                "Some phones (Xiaomi, Samsung, Huawei, OnePlus...) also need Autostart allowed or the app " +
                    "removed from \"sleeping apps\" in their own battery settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { shareDiagnostics(context) },
                modifier = Modifier.fillMaxWidth().testTag("settings_share_diagnostics")
            ) { Text("Share playback diagnostics") }
        }
    }
}

private fun shareDiagnostics(context: android.content.Context) {
    val report = AppDiagnostics.buildReport(context)
    if (report == null) {
        Toast.makeText(context, "Couldn't create the diagnostics report", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", report)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Fanqie Translate playback diagnostics")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't share the diagnostics report", Toast.LENGTH_SHORT).show()
    }
}
