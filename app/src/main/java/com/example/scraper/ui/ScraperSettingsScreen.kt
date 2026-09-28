package com.example.scraper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scraper.model.ScrapeSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperSettingsScreen(viewModel: ScraperViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scraper settings") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ScraperSettingsContent(viewModel)
        }
    }
}

/** Settings form, also embedded in the host app's settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperSettingsContent(viewModel: ScraperViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val learned by viewModel.learnedLayouts.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val update: ((ScrapeSettings) -> ScrapeSettings) -> Unit = { viewModel.updateSettings(it) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Pacing")
        var delay by remember(settings.delayMinMs, settings.delayMaxMs) {
            mutableStateOf((settings.delayMinMs / 1000f).coerceAtMost(120f)..(settings.delayMaxMs / 1000f).coerceAtMost(120f))
        }
        LabeledValue(
            "Delay between chapters",
            if (delay.start.roundToInt() == delay.endInclusive.roundToInt()) "${delay.start.roundToInt()} s"
            else "${delay.start.roundToInt()}–${delay.endInclusive.roundToInt()} s, random"
        )
        RangeSlider(
            value = delay,
            onValueChange = { delay = it.start.roundToInt().toFloat()..it.endInclusive.roundToInt().toFloat() },
            valueRange = 0f..120f,
            onValueChangeFinished = { update { it.copy(delayMinMs = delay.start.roundToLong() * 1000, delayMaxMs = delay.endInclusive.roundToLong() * 1000) } },
            modifier = Modifier.testTag("settings_delay_slider")
        )
        Hint("A random wait in this range is used after each saved chapter, so requests don't look automated. You can also change it from the scrape panel while scraping.")

        NumberField("Stop after this many chapters per run (0 = no limit)", settings.maxChaptersPerRun) { v ->
            update { it.copy(maxChaptersPerRun = v) }
        }
        NumberField("Most chapters per hour from one site (0 = no limit)", settings.maxChaptersPerHour) { v ->
            update { it.copy(maxChaptersPerHour = v) }
        }
        NumberField("Most chapters per day from one site (0 = no limit)", settings.maxChaptersPerDay) { v ->
            update { it.copy(maxChaptersPerDay = v) }
        }
        Hint("TomatoMTL may restrict accounts that read more than 100 chapters an hour or 676 a day. When the hourly limit is reached the scraper waits; the daily limit pauses the scrape until you resume it.")

        HorizontalDivider()
        SectionTitle("Content")
        SliderSetting(
            label = "Wait until the page stops changing",
            valueText = { "${"%.1f".format(it)} s" },
            value = settings.stabilizationMs / 1000f,
            range = 0f..15f,
            steps = 29
        ) { v -> update { it.copy(stabilizationMs = (v * 1000).roundToLong()) } }
        Hint("Text is saved only after it has stayed the same for this long. It lets pages finish loading and live translation finish.")

        SwitchSetting(
            label = "Wait for English translation",
            description = "For sites like TomatoMTL that translate Chinese text in the page. Turn off for sites you want to save in Chinese.",
            checked = settings.translationWaitMs > 0
        ) { on -> update { it.copy(translationWaitMs = if (on) 30_000L else 0L) } }
        if (settings.translationWaitMs > 0) {
            SliderSetting(
                label = "Longest wait for translation",
                valueText = { "${it.roundToInt()} s" },
                value = settings.translationWaitMs / 1000f,
                range = 5f..120f,
                steps = 22
            ) { v -> update { it.copy(translationWaitMs = (v * 1000).roundToLong()) } }
        }
        SliderSetting(
            label = "Longest wait for a chapter",
            valueText = { "${it.roundToInt()} s" },
            value = settings.maxChapterWaitMs / 1000f,
            range = 15f..300f,
            steps = 56
        ) { v -> update { it.copy(maxChapterWaitMs = (v * 1000).roundToLong()) } }
        NumberField("Minimum characters for a chapter", settings.minCharacterCount) { v ->
            update { it.copy(minCharacterCount = v) }
        }
        SliderSetting(
            label = "Retries before stopping on a failing page",
            valueText = { "${it.roundToInt()}" },
            value = settings.maxRetryCount.toFloat(),
            range = 0f..10f,
            steps = 9
        ) { v -> update { it.copy(maxRetryCount = v.roundToInt()) } }
        SwitchSetting(
            label = "Scroll pages while waiting",
            description = "Helps pages that only load or translate text when it scrolls into view.",
            checked = settings.autoScroll
        ) { on -> update { it.copy(autoScroll = on) } }

        HorizontalDivider()
        SectionTitle("Browser")
        var baseUrl by remember(settings.defaultBaseUrl) { mutableStateOf(settings.defaultBaseUrl) }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Start page") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        if (baseUrl.trim() != settings.defaultBaseUrl) {
            OutlinedButton(onClick = { update { it.copy(defaultBaseUrl = baseUrl) } }) { Text("Save start page") }
        }
        SwitchSetting(
            label = "Keep scraping in the background",
            description = "Shows a notification and keeps going with the screen off. Android may limit background time for some phones.",
            checked = settings.backgroundScraping
        ) { on -> update { it.copy(backgroundScraping = on) } }

        HorizontalDivider()
        SectionTitle("Maintenance")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learned page layouts: $learned", style = MaterialTheme.typography.bodyMedium)
                Hint("Reset if a site changed its design and chapters come out wrong.")
            }
            OutlinedButton(onClick = { viewModel.clearLearnedLayouts() }, enabled = learned > 0) { Text("Reset") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Browsing history: ${history.size} pages", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.clearHistory() }, enabled = history.isNotEmpty()) { Text("Clear") }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit
) {
    var current by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    // Snap to the step grid ourselves; Slider(steps = n) would draw n tick marks across the track.
    val stepSize = (range.endInclusive - range.start) / (steps + 1)
    Column {
        LabeledValue(label, valueText(current))
        Slider(
            value = current,
            onValueChange = { raw ->
                current = if (stepSize > 0f) {
                    (range.start + Math.round((raw - range.start) / stepSize) * stepSize).coerceIn(range.start, range.endInclusive)
                } else raw
            },
            valueRange = range,
            onValueChangeFinished = { onCommit(current) }
        )
    }
}

@Composable
private fun SwitchSetting(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(label) },
            isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
        )
        if (parsed != null && parsed != value) {
            OutlinedButton(onClick = { onCommit(parsed) }) { Text("Save") }
        }
    }
}
