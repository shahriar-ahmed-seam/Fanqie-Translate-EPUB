package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.repository.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSaveSettings: (AppSettings) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    var workerCount by remember(settings) { mutableFloatStateOf(settings.workerCount.toFloat()) }
    var maxActiveBooks by remember(settings) { mutableFloatStateOf(settings.maxActiveBooks.toFloat()) }
    var chunkSize by remember(settings) { mutableFloatStateOf(settings.chunkSize.toFloat()) }
    var maxRetries by remember(settings) { mutableFloatStateOf(settings.maxRetries.toFloat()) }
    var timeoutSeconds by remember(settings) { mutableFloatStateOf(settings.timeoutSeconds.toFloat()) }
    var githubOwner by remember(settings) { mutableStateOf(settings.githubOwner) }
    var githubRepo by remember(settings) { mutableStateOf(settings.githubRepo) }
    var autoCheckUpdates by remember(settings) { mutableStateOf(settings.autoCheckUpdates) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Worker Concurrency Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Translation Concurrency",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Concurrent Workers (1-50):")
                        Text("${workerCount.toInt()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = workerCount,
                        onValueChange = { workerCount = it },
                        valueRange = 1f..50f,
                        steps = 48,
                        modifier = Modifier.testTag("worker_count_slider")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Active Books (1-5):")
                        Text("${maxActiveBooks.toInt()}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = maxActiveBooks,
                        onValueChange = { maxActiveBooks = it },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.testTag("active_books_slider")
                    )
                }
            }

            // Chunk & Network Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Chunking & Retries",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("TomatoMTL Safe Chunk Size:")
                        Text("${chunkSize.toInt()} chars", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = chunkSize,
                        onValueChange = { chunkSize = it },
                        valueRange = 1000f..4800f,
                        steps = 37,
                        modifier = Modifier.testTag("chunk_size_slider")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Retries (on error):")
                        Text("${maxRetries.toInt()} attempts", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = maxRetries,
                        onValueChange = { maxRetries = it },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Request Timeout:")
                        Text("${timeoutSeconds.toInt()}s", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = timeoutSeconds,
                        onValueChange = { timeoutSeconds = it },
                        valueRange = 5f..120f,
                        steps = 22
                    )
                }
            }

            // Updates & GitHub Integration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "In-App Updates (GitHub Releases)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = githubOwner,
                        onValueChange = { githubOwner = it },
                        label = { Text("GitHub Owner / Org") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = { githubRepo = it },
                        label = { Text("Repository Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Auto-check on startup", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = autoCheckUpdates,
                            onCheckedChange = { autoCheckUpdates = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Current: v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onCheckForUpdates,
                            modifier = Modifier.testTag("check_updates_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Updates")
                        }
                    }
                }
            }

            // Save Settings Button
            Button(
                onClick = {
                    onSaveSettings(
                        AppSettings(
                            workerCount = workerCount.toInt(),
                            maxActiveBooks = maxActiveBooks.toInt(),
                            chunkSize = chunkSize.toInt(),
                            maxRetries = maxRetries.toInt(),
                            timeoutSeconds = timeoutSeconds.toInt(),
                            githubOwner = githubOwner.trim(),
                            githubRepo = githubRepo.trim(),
                            autoCheckUpdates = autoCheckUpdates
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_settings_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Settings")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
