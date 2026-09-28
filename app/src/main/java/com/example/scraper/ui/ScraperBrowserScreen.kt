package com.example.scraper.ui

import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scraper.browser.WebViewBrowserEngine
import com.example.scraper.model.ScrapeProgress
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ScrapeStatus
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperBrowserScreen(viewModel: ScraperViewModel) {
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val chooser by viewModel.chooser.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var address by remember { mutableStateOf(state.url) }
    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.url, editing) {
        if (!editing) address = state.url
    }

    BackHandler {
        if (editing) {
            focusManager.clearFocus()
        } else if (!viewModel.browser.goBack()) {
            viewModel.back()
        }
    }

    fun go() {
        focusManager.clearFocus()
        if (address.isNotBlank()) viewModel.browser.loadUrl(address)
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.back() }, modifier = Modifier.testTag("browser_close_button")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to scraper")
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .onFocusChanged { editing = it.isFocused }
                                .testTag("browser_url_input"),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            shape = RoundedCornerShape(24.dp),
                            placeholder = { Text("Search or type a URL", fontSize = 13.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go() })
                        )
                        if (state.isLoading) {
                            IconButton(onClick = { viewModel.browser.stopLoading() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop loading")
                            }
                        } else {
                            IconButton(onClick = { if (editing && address != state.url) go() else viewModel.browser.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("browser_menu_button")) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Forward") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                                    enabled = state.canGoForward,
                                    onClick = { menuOpen = false; viewModel.browser.goForward() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy link") },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                    enabled = state.url.isNotBlank(),
                                    onClick = {
                                        menuOpen = false
                                        clipboard.setText(AnnotatedString(state.url))
                                        viewModel.showMessage("Link copied")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share link") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    enabled = state.url.startsWith("http"),
                                    onClick = {
                                        menuOpen = false
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, state.url)
                                            putExtra(Intent.EXTRA_SUBJECT, state.title)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share link"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste and go") },
                                    leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                    onClick = {
                                        menuOpen = false
                                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                        if (pasted.isNotEmpty()) viewModel.browser.loadUrl(pasted) else viewModel.showMessage("Clipboard is empty")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("History") },
                                    leadingIcon = { Icon(Icons.Default.History, null) },
                                    onClick = { menuOpen = false; viewModel.open(ScraperScreen.History) }
                                )
                                progress.projectId?.let { projectId ->
                                    DropdownMenuItem(
                                        text = { Text("Open project") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                        onClick = { menuOpen = false; viewModel.open(ScraperScreen.Project(projectId)) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Scraper settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { menuOpen = false; viewModel.open(ScraperScreen.Settings) }
                                )
                            }
                        }
                    }
                    if (state.isLoading) {
                        LinearProgressIndicator(progress = { (state.progress / 100f).coerceIn(0.05f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        HorizontalDivider()
                    }
                }
            }
        },
        bottomBar = {
            ScrapePanel(
                progress = progress,
                settings = settings,
                logs = logs,
                canStart = state.url.startsWith("http"),
                viewModel = viewModel
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            BrowserView(viewModel.browser, Modifier.fillMaxSize())
            if (!state.url.startsWith("http") && !state.isLoading) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Type a website or chapter URL above", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { viewModel.browser.loadUrl(settings.defaultBaseUrl) }) {
                        Text("Open ${settings.defaultBaseUrl.removePrefix("https://")}")
                    }
                }
            }
            state.mainFrameError?.let { error ->
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load the page: $error", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.browser.reload() }) { Text("Reload") }
                    }
                }
            }
        }
    }

    chooser?.let { chooserState ->
        ProjectChooserSheet(
            state = chooserState,
            onNew = { viewModel.startNewProject(it) },
            onExisting = { viewModel.startInProject(it) },
            onDismiss = { viewModel.dismissChooser() }
        )
    }
}

@Composable
private fun BrowserView(engine: WebViewBrowserEngine, modifier: Modifier) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx).also { engine.attach(it, ctx) } },
        onRelease = { engine.detach(it) },
        modifier = modifier.testTag("browser_webview")
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrapePanel(
    progress: ScrapeProgress,
    settings: ScrapeSettings,
    logs: List<com.example.scraper.model.ScrapeLogEntry>,
    canStart: Boolean,
    viewModel: ScraperViewModel
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    val active = progress.status.isActive
    val canResume = !active && progress.projectId != null &&
        (progress.status == ScrapeStatus.PAUSED || progress.status == ScrapeStatus.NEEDS_LOGIN || progress.status == ScrapeStatus.ERROR)

    if (confirmStop) {
        ConfirmDialog(
            title = "Stop scraping?",
            message = "Saved chapters stay in the project. You can resume later from the next chapter.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = { confirmStop = false; viewModel.stop() },
            onDismiss = { confirmStop = false }
        )
    }
    if (confirmSkip) {
        ConfirmDialog(
            title = "Skip this page?",
            message = "This page won't be saved. The scraper opens the next chapter instead.",
            confirmLabel = "Skip",
            onConfirm = { confirmSkip = false; viewModel.skip() },
            onDismiss = { confirmSkip = false }
        )
    }

    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(progress.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = progress.chapterTitle.ifBlank { progress.message },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!expanded) {
                    when {
                        active -> IconButton(onClick = { viewModel.pause() }) { Icon(Icons.Default.Pause, contentDescription = "Pause") }
                        canResume -> IconButton(onClick = { viewModel.resume() }) { Icon(Icons.Default.PlayArrow, contentDescription = "Resume") }
                        else -> IconButton(onClick = { viewModel.requestStart() }) { Icon(Icons.Default.PlayArrow, contentDescription = "Start") }
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (expanded) "Collapse panel" else "Expand panel",
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (progress.projectTitle.isNotBlank()) "Project: ${progress.projectTitle}" else "Project: you'll pick one when you tap Start",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${progress.chaptersThisRun} new this run · ${progress.totalChapters} total")
                            if (progress.characterCount > 0) append(" · last ${formatCount(progress.characterCount.toLong())} chars")
                            if (progress.retryCount > 0) append(" · retry ${progress.retryCount}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(progress.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("scrape_status_message"))
                    if (progress.status == ScrapeStatus.COOLDOWN && progress.delayTotalMs > 0) {
                        val fraction = 1f - progress.delayRemainingMs.toFloat() / progress.delayTotalMs.toFloat()
                        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    DelayControl(settings) { min, max -> viewModel.setDelayRange(min, max) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            active -> {
                                Button(onClick = { viewModel.pause() }, modifier = Modifier.testTag("scrape_pause_button")) {
                                    Icon(Icons.Default.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Pause")
                                }
                                OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.testTag("scrape_stop_button")) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop")
                                }
                                OutlinedButton(onClick = { confirmSkip = true }) {
                                    Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Skip")
                                }
                            }
                            canResume -> {
                                Button(onClick = { viewModel.resume() }, modifier = Modifier.testTag("scrape_resume_button")) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Resume")
                                }
                                OutlinedButton(onClick = { viewModel.retry() }) {
                                    Icon(Icons.Default.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Retry page")
                                }
                                OutlinedButton(onClick = { confirmSkip = true }) {
                                    Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Skip")
                                }
                                OutlinedButton(onClick = { confirmStop = true }) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop")
                                }
                            }
                            else -> {
                                Button(
                                    onClick = { viewModel.requestStart() },
                                    enabled = canStart,
                                    modifier = Modifier.testTag("scrape_start_button")
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Start")
                                }
                            }
                        }
                        progress.projectId?.let { projectId ->
                            FilledTonalButton(onClick = { viewModel.open(ScraperScreen.Project(projectId)) }) { Text("Project") }
                        }
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide log" else "Show log") }
                    }
                    if (showLogs) LiveLog(logs)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelayControl(settings: ScrapeSettings, onChange: (Long, Long) -> Unit) {
    var range by remember(settings.delayMinMs, settings.delayMaxMs) {
        mutableStateOf((settings.delayMinMs / 1000f).coerceIn(0f, MAX_DELAY_SLIDER_S)..(settings.delayMaxMs / 1000f).coerceIn(0f, MAX_DELAY_SLIDER_S))
    }
    Column {
        val min = range.start.roundToLong()
        val max = range.endInclusive.roundToLong()
        Text(
            text = if (min == max) "Delay between chapters: ${min}s" else "Delay between chapters: $min–${max}s (random)",
            style = MaterialTheme.typography.bodySmall
        )
        // Continuous slider snapped to whole seconds (a stepped slider would draw 60 tick marks).
        RangeSlider(
            value = range,
            onValueChange = { range = it.start.roundToLong().toFloat()..it.endInclusive.roundToLong().toFloat() },
            valueRange = 0f..MAX_DELAY_SLIDER_S,
            onValueChangeFinished = { onChange(range.start.roundToLong() * 1000, range.endInclusive.roundToLong() * 1000) },
            modifier = Modifier.testTag("scrape_delay_slider")
        )
    }
}

private const val MAX_DELAY_SLIDER_S = 60f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectChooserSheet(
    state: ProjectChooserState,
    onNew: (String) -> Unit,
    onExisting: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(state.suggestedTitle) { mutableStateOf(state.suggestedTitle) }
    val suggested = state.projects.firstOrNull { it.id == state.suggestedProjectId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Start scraping", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "This page and the chapters after it are saved into the project you pick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (suggested != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Continue \"${suggested.title}\"", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SmallLabel("${suggested.chapterCount} chapters saved · ${suggested.siteHost}")
                        Button(onClick = { onExisting(suggested.id) }, modifier = Modifier.padding(top = 6.dp).testTag("chooser_continue_suggested")) {
                            Text("Continue this project")
                        }
                    }
                }
            }
            Text("New project", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().testTag("chooser_title_input"),
                singleLine = true,
                label = { Text("Novel title") }
            )
            Button(onClick = { onNew(title) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("chooser_new_project")) {
                Text("Create project and start")
            }
            val others = state.projects.filter { it.id != suggested?.id }
            if (others.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Or add to another project", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                others.take(30).forEach { project ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExisting(project.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SmallLabel("${project.chapterCount} chapters · ${project.siteHost}")
                        }
                    }
                }
            }
        }
    }
}
