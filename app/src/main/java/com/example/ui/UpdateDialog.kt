package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.update.ReleaseInfo
import java.io.File

/** Where the in-app update is. Every step has a way forward or out: nothing waits forever at 100%. */
sealed interface UpdateStep {
    data object Available : UpdateStep
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : UpdateStep
    /** Downloaded and verified (package, version, signing key). */
    data class ReadyToInstall(val apk: File) : UpdateStep
    data class Failed(val message: String, val canRetry: Boolean) : UpdateStep
}

data class UpdateUiState(val info: ReleaseInfo, val step: UpdateStep)

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    canInstallPackages: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit
) {
    val releaseInfo = state.info
    val step = state.step
    val downloading = step is UpdateStep.Downloading
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val isCompactHeight = screenHeightDp < 500
    val maxDialogHeight = (screenHeightDp * 0.90f).dp

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (!downloading) onDismiss() }
                .padding(horizontal = 16.dp, vertical = if (isCompactHeight) 8.dp else 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 480.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* prevent dismissal */ },
                shape = RoundedCornerShape(if (isCompactHeight) 20.dp else 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 24.dp,
                                end = 16.dp,
                                top = if (isCompactHeight) 12.dp else 18.dp,
                                bottom = if (isCompactHeight) 8.dp else 12.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (step) {
                                    is UpdateStep.ReadyToInstall -> "Ready to Install"
                                    is UpdateStep.Failed -> "Update Problem"
                                    else -> "Update Available"
                                },
                                style = if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp).testTag("update_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = if (downloading) "Cancel download" else "Close",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = if (isCompactHeight) 18.dp else 24.dp,
                                vertical = if (isCompactHeight) 10.dp else 16.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Version ${releaseInfo.versionName} (Current: ${BuildConfig.VERSION_NAME})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        when (step) {
                            is UpdateStep.Downloading -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth().testTag("update_progress"),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val progress = step.progress
                                    if (progress != null) {
                                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (progress == null) "Downloading update..." else if (progress >= 1f) "Verifying the download..." else "Downloading: ${(progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            is UpdateStep.ReadyToInstall -> {
                                Text(
                                    text = if (canInstallPackages) {
                                        "Downloaded and verified. Tap Install and confirm in the Android installer. Your library and settings are kept."
                                    } else {
                                        "Downloaded and verified. Android first needs permission to install apps from Fanqie Translate: " +
                                            "tap Install, turn on \"Allow from this source\", come back and tap Install again."
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            is UpdateStep.Failed -> {
                                Text(
                                    text = step.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("update_error")
                                )
                            }
                            UpdateStep.Available -> Unit
                        }

                        // Release Notes Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Release Notes:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = releaseInfo.releaseNotes.ifBlank { "Maintenance and performance improvements." },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Sticky Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isCompactHeight) 18.dp else 24.dp,
                                vertical = if (isCompactHeight) 10.dp else 14.dp
                            ),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(if (downloading) "Cancel" else "Later")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        when (step) {
                            UpdateStep.Available -> Button(
                                onClick = onDownload,
                                enabled = releaseInfo.apkDownloadUrl != null,
                                modifier = Modifier.testTag("update_install_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download")
                            }
                            is UpdateStep.ReadyToInstall -> Button(
                                onClick = { onInstall(step.apk) },
                                modifier = Modifier.testTag("update_launch_installer_button")
                            ) {
                                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Install")
                            }
                            is UpdateStep.Failed -> if (step.canRetry) {
                                Button(onClick = onDownload, modifier = Modifier.testTag("update_retry_button")) {
                                    Text("Try again")
                                }
                            }
                            is UpdateStep.Downloading -> Unit
                        }
                    }
                }
            }
        }
    }
}
