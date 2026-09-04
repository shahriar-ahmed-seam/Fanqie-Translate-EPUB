package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.TranslatorApplication
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.ui.theme.TomatoRedPrimary
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSaveSettings: (AppSettings) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    var isDarkMode by remember(settings) { mutableStateOf(settings.isDarkMode) }
    var workerCount by remember(settings) { mutableFloatStateOf(settings.workerCount.toFloat()) }
    var maxActiveBooks by remember(settings) { mutableFloatStateOf(settings.maxActiveBooks.toFloat()) }
    var chunkSize by remember(settings) { mutableFloatStateOf(settings.chunkSize.toFloat()) }
    var maxRetries by remember(settings) { mutableFloatStateOf(settings.maxRetries.toFloat()) }
    var timeoutSeconds by remember(settings) { mutableFloatStateOf(settings.timeoutSeconds.toFloat()) }
    var githubOwner by remember(settings) { mutableStateOf(settings.githubOwner) }
    var githubRepo by remember(settings) { mutableStateOf(settings.githubRepo) }
    var autoCheckUpdates by remember(settings) { mutableStateOf(settings.autoCheckUpdates) }

    val context = LocalContext.current
    val app = context.applicationContext as? TranslatorApplication
    val settingsRepo = remember { app?.settingsRepository ?: SettingsRepository(context) }
    val ttsManager = app?.ttsManager

    val availableVoices by ttsManager?.availableVoices?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val selectedVoice by ttsManager?.selectedVoice?.collectAsState() ?: remember { mutableStateOf(null) }

    var currentVoiceId by remember { mutableStateOf(settingsRepo.getTtsVoiceId()) }
    var currentSpeechRate by remember { mutableFloatStateOf(settingsRepo.getTtsSpeechRate()) }
    var autoResumePlayback by remember { mutableStateOf(settingsRepo.isTtsAutoResumePlaybackEnabled()) }
    var autoAdvanceChapter by remember { mutableStateOf(settingsRepo.isTtsAutoAdvanceChapterEnabled()) }
    var showVoiceDropdown by remember { mutableStateOf(false) }

    fun buildCurrentSettings(): AppSettings {
        return AppSettings(
            workerCount = workerCount.toInt(),
            maxActiveBooks = maxActiveBooks.toInt(),
            chunkSize = chunkSize.toInt(),
            maxRetries = maxRetries.toInt(),
            timeoutSeconds = timeoutSeconds.toInt(),
            githubOwner = githubOwner.trim(),
            githubRepo = githubRepo.trim(),
            autoCheckUpdates = autoCheckUpdates,
            isDarkMode = isDarkMode
        )
    }

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
            // Appearance & Theme Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Appearance & Theme",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Tomato Light Theme Card Option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    isDarkMode = false
                                    onSaveSettings(buildCurrentSettings().copy(isDarkMode = false))
                                }
                                .testTag("theme_tomato_light_button"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isDarkMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (!isDarkMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LightMode,
                                    contentDescription = "Tomato Light",
                                    tint = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tomato Light",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (!isDarkMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (!isDarkMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "White & Red",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        // Dark Mode Card Option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    isDarkMode = true
                                    onSaveSettings(buildCurrentSettings().copy(isDarkMode = true))
                                }
                                .testTag("theme_dark_button"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDarkMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isDarkMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DarkMode,
                                    contentDescription = "Dark Mode",
                                    tint = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Dark Mode",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isDarkMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isDarkMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Night Theme",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // Text-to-Speech (TTS) Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Text-to-Speech (Audiobook)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 1. Voice Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Default Voice",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Preferred voice for reading aloud translated chapters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val activeVoiceDisplayName = if (currentVoiceId.isNullOrBlank()) {
                            "System Default"
                        } else {
                            availableVoices.find { it.id == currentVoiceId }?.displayName ?: "System Default"
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedCard(
                                onClick = { showVoiceDropdown = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tts_voice_selector_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RecordVoiceOver,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = activeVoiceDisplayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select voice",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showVoiceDropdown,
                                onDismissRequest = { showVoiceDropdown = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "System Default",
                                            fontWeight = if (currentVoiceId.isNullOrBlank()) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        currentVoiceId = null
                                        settingsRepo.setTtsVoiceId(null)
                                        ttsManager?.selectVoiceById(null)
                                        showVoiceDropdown = false
                                    },
                                    trailingIcon = if (currentVoiceId.isNullOrBlank()) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )

                                if (availableVoices.isNotEmpty()) {
                                    HorizontalDivider()
                                    availableVoices.forEach { voice ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = voice.displayName,
                                                        fontWeight = if (voice.id == currentVoiceId) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (voice.isNetworkRequired) {
                                                        Text(
                                                            text = "Network required",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.outline
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                currentVoiceId = voice.id
                                                settingsRepo.setTtsVoiceId(voice.id)
                                                ttsManager?.selectVoice(voice)
                                                showVoiceDropdown = false
                                            },
                                            trailingIcon = if (voice.id == currentVoiceId) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Playback Speed
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Playback Speed",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(Locale.US, "%.2fx", currentSpeechRate),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = currentSpeechRate,
                            onValueChange = {
                                val rounded = (Math.round(it * 20f) / 20f).coerceIn(0.5f, 2.5f)
                                currentSpeechRate = rounded
                                settingsRepo.setTtsSpeechRate(rounded)
                                ttsManager?.setSpeechRate(rounded)
                            },
                            valueRange = 0.5f..2.5f,
                            steps = 39,
                            modifier = Modifier.testTag("tts_speed_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                                FilterChip(
                                    selected = Math.abs(currentSpeechRate - preset) < 0.04f,
                                    onClick = {
                                        currentSpeechRate = preset
                                        settingsRepo.setTtsSpeechRate(preset)
                                        ttsManager?.setSpeechRate(preset)
                                    },
                                    label = { Text("${preset}x") },
                                    modifier = Modifier.testTag("tts_speed_preset_${preset}")
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 3. Resume TTS automatically
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Resume TTS automatically",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Restore audio playback when reopening the app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoResumePlayback,
                            onCheckedChange = {
                                autoResumePlayback = it
                                settingsRepo.setTtsAutoResumePlaybackEnabled(it)
                            },
                            modifier = Modifier.testTag("tts_auto_resume_switch")
                        )
                    }

                    // 4. Continue to next chapter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Continue to next chapter",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatically advance and speak subsequent chapters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoAdvanceChapter,
                            onCheckedChange = {
                                autoAdvanceChapter = it
                                settingsRepo.setTtsAutoAdvanceChapterEnabled(it)
                                ttsManager?.setAutoAdvanceChapter(it)
                            },
                            modifier = Modifier.testTag("tts_auto_advance_switch")
                        )
                    }
                }
            }

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
                    Text(
                        text = "Global parallel translation requests distributed across active books",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    Text(
                        text = "Maximum novels translating concurrently",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        Text("Safe Chunk Size:")
                        Text("${chunkSize.toInt()} chars", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = "Target character length per text chunk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    Text(
                        text = "Retry attempts for transient network errors before marking chunk failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    Text(
                        text = "Maximum wait time per HTTP request",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                onClick = { onSaveSettings(buildCurrentSettings()) },
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
