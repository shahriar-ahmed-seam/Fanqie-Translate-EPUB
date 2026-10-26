package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scraper.ScraperGraph
import com.example.scraper.ui.ScraperRoot
import com.example.scraper.ui.ScraperScreen
import com.example.scraper.ui.ScraperViewModel
import com.example.queue.ImportProgress
import com.example.tts.TtsPlaybackService
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import java.io.File

/** Job states that count as "in the queue" for the Queue tab badge (workers set RUNNING while translating). */
val ACTIVE_JOB_STATUSES = setOf("QUEUED", "RUNNING", "TRANSLATING", "PAUSING")

enum class Screen(val title: String) {
    HOME("Home"),
    LIBRARY("Library"),
    TOMATO("Tomato"),
    SCRAPER("Scraper"),
    QUEUE("Queue"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    val pendingNotificationBookId = mutableStateOf<String?>(null)
    val pendingNotificationChapterId = mutableStateOf<String?>(null)
    /** Set when the scraper's notification is tapped. */
    val pendingOpenScraper = mutableStateOf(false)
    /** Set when the Tomato download notification is tapped. */
    val pendingOpenTomato = mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A recreated activity (process death, rotation) or a relaunch from Recents gets the ORIGINAL
        // launch intent again; its extras point at wherever TTS was hours ago. Only a fresh launch counts.
        val launchedFromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            handleOpenIntent(intent)
        } else {
            consumeOpenExtras(intent)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = settings.isDarkMode) {
                MainApp(
                    viewModel = viewModel,
                    pendingNotificationBookId = pendingNotificationBookId,
                    pendingNotificationChapterId = pendingNotificationChapterId,
                    pendingOpenScraper = pendingOpenScraper,
                    pendingOpenTomato = pendingOpenTomato
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        if (intent == null) return
        val opensTtsSession = intent.getBooleanExtra(TtsPlaybackService.EXTRA_OPEN_TTS_SESSION, false)
        val bId = intent.getStringExtra(TtsPlaybackService.EXTRA_OPEN_BOOK_ID)
        val cId = intent.getStringExtra(TtsPlaybackService.EXTRA_OPEN_CHAPTER_ID)
        val target = if (opensTtsSession) resolveTtsSessionTarget(bId, cId) else if (bId != null && cId != null) bId to cId else null
        if (target != null) {
            pendingNotificationBookId.value = target.first
            pendingNotificationChapterId.value = target.second
        }
        if (intent.getBooleanExtra(ScraperGraph.EXTRA_OPEN_SCRAPER, false)) {
            pendingOpenScraper.value = true
        }
        if (intent.getBooleanExtra(com.example.tomato.TomatoDownloadService.EXTRA_OPEN_TOMATO, false)) {
            pendingOpenTomato.value = true
        }
        consumeOpenExtras(intent)
    }

    /**
     * Where the listener is right now, resolved when the notification is tapped rather than when it
     * was built: the live session first, then the saved checkpoint, then what the intent carried.
     */
    private fun resolveTtsSessionTarget(fallbackBookId: String?, fallbackChapterId: String?): Pair<String, String>? {
        val app = application as? TranslatorApplication
        val manager = app?.ttsManager
        val meta = manager?.mediaMetadata?.value
        if (manager != null && manager.hasActiveSession() && meta != null) return meta.bookId to meta.chapterId
        val session = app?.settingsRepository?.getTtsSessionState()
        if (session != null && session.bookId.isNotBlank() && session.chapterId.isNotBlank()) {
            return session.bookId to session.chapterId
        }
        return if (fallbackBookId != null && fallbackChapterId != null) fallbackBookId to fallbackChapterId else null
    }

    private fun consumeOpenExtras(intent: Intent?) {
        intent?.removeExtra(TtsPlaybackService.EXTRA_OPEN_TTS_SESSION)
        intent?.removeExtra(TtsPlaybackService.EXTRA_OPEN_BOOK_ID)
        intent?.removeExtra(TtsPlaybackService.EXTRA_OPEN_CHAPTER_ID)
        intent?.removeExtra(ScraperGraph.EXTRA_OPEN_SCRAPER)
        intent?.removeExtra(com.example.tomato.TomatoDownloadService.EXTRA_OPEN_TOMATO)
    }
}

@Composable
fun MainApp(
    viewModel: MainViewModel,
    pendingNotificationBookId: MutableState<String?>? = null,
    pendingNotificationChapterId: MutableState<String?>? = null,
    pendingOpenScraper: MutableState<Boolean>? = null,
    pendingOpenTomato: MutableState<Boolean>? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? TranslatorApplication
    val settingsRepo = remember { app?.settingsRepository ?: com.example.data.repository.SettingsRepository(context) }
    val ttsManager = remember { app?.ttsManager }

    val liveTtsMeta = ttsManager?.takeIf { it.hasActiveSession() }?.mediaMetadata?.value

    val initialBookId = remember {
        val intentBook = pendingNotificationBookId?.value
        val activeTtsBook = liveTtsMeta?.bookId?.takeIf { it.isNotBlank() }
        val sessionState = if (settingsRepo.isTtsAutoResumePlaybackEnabled()) settingsRepo.getTtsSessionState() else null
        val autoResumeBook = sessionState?.bookId?.takeIf { it.isNotBlank() }
        val lastBook = settingsRepo.getLastActiveBookId()
        intentBook ?: activeTtsBook ?: autoResumeBook ?: lastBook
    }

    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedBookIdForDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var activeReaderBookId by rememberSaveable { mutableStateOf(initialBookId) }
    // Deliberately not saveable: a chapter restored from saved UI state can be hours behind the TTS
    // session (it kept advancing with the screen off). Resolved from the newest data instead:
    // an explicit request, the live session, then the progress saved for that book.
    var activeReaderChapterId by remember {
        mutableStateOf(
            activeReaderBookId?.let { bookId ->
                pendingNotificationChapterId?.value?.takeIf { pendingNotificationBookId?.value == bookId }
                    ?: liveTtsMeta?.takeIf { it.bookId == bookId }?.chapterId?.takeIf { it.isNotBlank() }
                    ?: settingsRepo.getLastReadChapterId(bookId)
            }
        )
    }

    val scraperViewModel: ScraperViewModel = viewModel()
    val scraperScreen by scraperViewModel.screen.collectAsStateWithLifecycle()
    // The browser needs the whole screen, so the bottom bar hides while it's open.
    val scraperFullScreen = currentScreen == Screen.SCRAPER && scraperScreen == ScraperScreen.Browser

    LaunchedEffect(pendingOpenScraper?.value) {
        if (pendingOpenScraper?.value == true) {
            activeReaderBookId = null
            activeReaderChapterId = null
            selectedBookIdForDetail = null
            currentScreen = Screen.SCRAPER
            scraperViewModel.openBrowser()
            pendingOpenScraper.value = false
        }
    }

    LaunchedEffect(pendingOpenTomato?.value) {
        if (pendingOpenTomato?.value == true) {
            activeReaderBookId = null
            activeReaderChapterId = null
            selectedBookIdForDetail = null
            currentScreen = Screen.TOMATO
            pendingOpenTomato.value = false
        }
    }

    // Handle new intent notification click while activity is alive
    LaunchedEffect(pendingNotificationBookId?.value, pendingNotificationChapterId?.value) {
        val bId = pendingNotificationBookId?.value
        val cId = pendingNotificationChapterId?.value
        if (bId != null && cId != null) {
            activeReaderBookId = bId
            activeReaderChapterId = cId
            pendingNotificationBookId.value = null
            pendingNotificationChapterId.value = null
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val booksWithJobs by viewModel.allBooksWithJobs.collectAsStateWithLifecycle()
    val libraryGroups by viewModel.libraryGroups.collectAsStateWithLifecycle()
    val bookGroupCrossRefs by viewModel.bookGroupCrossRefs.collectAsStateWithLifecycle()
    val activeWorkers by viewModel.activeWorkers.collectAsStateWithLifecycle()
    val activeWorkersByJob by viewModel.activeWorkersByJob.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val exportingBookIds by viewModel.exportingBookIds.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val libraryViewMode by viewModel.libraryViewMode.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // Show Snackbars for messages
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // SAF Document Export Handler
    var pendingExportBookId by remember { mutableStateOf<String?>(null) }
    var pendingExportSourcePath by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { destinationUri: Uri? ->
        val bookId = pendingExportBookId
        val sourcePath = pendingExportSourcePath
        if (destinationUri != null && bookId != null) {
            viewModel.exportEpubToUri(context, bookId, sourcePath, destinationUri)
        }
        pendingExportBookId = null
        pendingExportSourcePath = null
    }

    val onExport: (String, String, String?) -> Unit = { bookId, bookTitle, sourcePath ->
        pendingExportBookId = bookId
        pendingExportSourcePath = sourcePath
        val safeName = bookTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "novel" }
        val exportFileName = if (safeName.endsWith(".epub", ignoreCase = true)) safeName else "$safeName.epub"
        exportLauncher.launch(exportFileName)
    }

    if (activeReaderBookId != null && activeReaderChapterId != null) {
        ReaderScreen(
            bookId = activeReaderBookId!!,
            initialChapterId = activeReaderChapterId!!,
            viewModel = viewModel,
            onNavigateBack = {
                activeReaderChapterId = null
                activeReaderBookId = null
                settingsRepo.setLastActiveBookId(null)
            }
        )
    } else if (selectedBookIdForDetail != null) {
        NovelDetailScreen(
            bookId = selectedBookIdForDetail!!,
            viewModel = viewModel,
            onNavigateBack = { selectedBookIdForDetail = null },
            onOpenReader = { chId ->
                activeReaderBookId = selectedBookIdForDetail
                activeReaderChapterId = chId
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!scraperFullScreen) NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                    NavigationBarItem(
                        selected = currentScreen == Screen.HOME,
                        onClick = { currentScreen = Screen.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        modifier = Modifier.testTag("nav_home")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.LIBRARY,
                        onClick = { currentScreen = Screen.LIBRARY },
                        icon = { Icon(Icons.Default.AutoStories, contentDescription = "Library") },
                        label = { Text("Library") },
                        modifier = Modifier.testTag("nav_library")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.TOMATO,
                        onClick = { currentScreen = Screen.TOMATO },
                        icon = { Text("\uD83C\uDF45", fontSize = 20.sp) },
                        label = { Text("Tomato") },
                        modifier = Modifier.testTag("nav_tomato")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.SCRAPER,
                        onClick = {
                            // Tapping the tab again returns to the scraper's first screen.
                            if (currentScreen == Screen.SCRAPER) scraperViewModel.goHome()
                            currentScreen = Screen.SCRAPER
                        },
                        icon = {
                            val scraping by scraperViewModel.progress.collectAsStateWithLifecycle()
                            BadgedBox(badge = { if (scraping.status.isActive) Badge() }) {
                                Icon(Icons.Default.TravelExplore, contentDescription = "Scraper")
                            }
                        },
                        label = { Text("Scraper") },
                        modifier = Modifier.testTag("nav_scraper")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.QUEUE,
                        onClick = { currentScreen = Screen.QUEUE },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val activeCount = booksWithJobs.count { it.job?.status in ACTIVE_JOB_STATUSES }
                                    if (activeCount > 0) {
                                        Badge { Text("$activeCount") }
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Queue")
                            }
                        },
                        label = { Text("Queue") },
                        modifier = Modifier.testTag("nav_queue")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { currentScreen = Screen.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        modifier = Modifier.testTag("nav_settings")
                    )
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(
                        booksWithJobs = booksWithJobs,
                        activeWorkers = activeWorkers,
                        activeWorkersByJob = activeWorkersByJob,
                        exportingBookIds = exportingBookIds,
                        isProcessing = isProcessing,
                        onSelectSingleEpub = { viewModel.previewSingleEpub(it, isLibraryIntent = false) },
                        onSelectMultipleEpubs = { viewModel.importMultipleEpubs(it) },
                        onNavigateToQueue = { currentScreen = Screen.QUEUE },
                        onOpenNovelDetail = { bookId -> selectedBookIdForDetail = bookId },
                        onExportEpub = onExport,
                        onPauseJob = { viewModel.pauseJob(it) },
                        onResumeJob = { viewModel.resumeJob(it) },
                        onRetryJob = { viewModel.retryFailed(it) },
                        onDeleteBook = { viewModel.deleteBook(it) }
                    )
                    Screen.LIBRARY -> LibraryScreen(
                        booksWithJobs = booksWithJobs,
                        libraryGroups = libraryGroups,
                        bookGroupCrossRefs = bookGroupCrossRefs,
                        exportingBookIds = exportingBookIds,
                        viewMode = libraryViewMode,
                        onToggleViewMode = { viewModel.setLibraryViewMode(it) },
                        onAddToLibrary = { viewModel.previewSingleEpub(it, isLibraryIntent = true) },
                        onCreateGroup = { viewModel.createGroup(it) },
                        onRenameGroup = { id, name -> viewModel.renameGroup(id, name) },
                        onDeleteGroup = { viewModel.deleteGroup(it) },
                        onSetBookGroups = { bookId, groups -> viewModel.setBookGroups(bookId, groups) },
                        onOpenNovelDetail = { bookId -> selectedBookIdForDetail = bookId },
                        onExportEpub = onExport,
                        onDeleteBook = { viewModel.deleteBook(it) }
                    )
                    Screen.QUEUE -> QueueScreen(
                        booksWithJobs = booksWithJobs,
                        activeWorkers = activeWorkers,
                        activeWorkersByJob = activeWorkersByJob,
                        exportingBookIds = exportingBookIds,
                        settings = settings,
                        onPauseJob = { viewModel.pauseJob(it) },
                        onResumeJob = { viewModel.resumeJob(it) },
                        onRetryJob = { viewModel.retryFailed(it) },
                        onCancelJob = { viewModel.cancelJob(it) },
                        onExportEpub = onExport
                    )
                    Screen.TOMATO -> com.example.tomato.ui.TomatoRoot(
                        viewModel = viewModel<com.example.tomato.ui.TomatoViewModel>(),
                        onOpenLibraryBook = { bookId -> selectedBookIdForDetail = bookId },
                        modifier = Modifier.consumeWindowInsets(innerPadding)
                    )
                    Screen.SCRAPER -> ScraperRoot(
                        viewModel = scraperViewModel,
                        modifier = Modifier.consumeWindowInsets(innerPadding),
                        onOpenLibraryBook = { bookId -> selectedBookIdForDetail = bookId }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSaveSettings = { viewModel.updateSettings(it) },
                        onCheckForUpdates = { viewModel.checkForUpdates(silent = false) },
                        viewModel = viewModel,
                        onOpenScraperSettings = {
                            scraperViewModel.goHome()
                            scraperViewModel.open(ScraperScreen.Settings)
                            currentScreen = Screen.SCRAPER
                        }
                    )
                }
            }
        }
    }

    // Book Preview Dialog
    previewState?.let { preview ->
        BookPreviewDialog(
            previewState = preview,
            onDismiss = { viewModel.closePreview() },
            onConfirm = { viewModel.enqueuePreviewedBook() },
            onAddToLibrary = { viewModel.addPreviewedBookToLibrary() }
        )
    }

    // In-App Update Dialog
    updateState?.let { update ->
        // Re-checked on every composition: the user may have just allowed installs in Settings.
        var canInstall by remember { mutableStateOf(viewModel.updateManager.canInstallPackages()) }
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    canInstall = viewModel.updateManager.canInstallPackages()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        UpdateDialog(
            state = update,
            canInstallPackages = canInstall,
            onDismiss = { viewModel.dismissUpdateDialog() },
            onDownload = { viewModel.startUpdateDownload() },
            onInstall = { apk ->
                val activity = context as? android.app.Activity
                when {
                    activity == null -> viewModel.onUpdateInstallFailed("Couldn't open the installer.")
                    !viewModel.updateManager.canInstallPackages() -> {
                        if (!viewModel.updateManager.openInstallPermissionSettings(activity)) {
                            viewModel.onUpdateInstallFailed(
                                "Allow \"Install unknown apps\" for Fanqie Translate in Android Settings, then try again."
                            )
                        }
                    }
                    !viewModel.updateManager.launchInstaller(activity, apk) ->
                        viewModel.onUpdateInstallFailed("Couldn't open the Android installer. Please try again.")
                }
            }
        )
    }

    // Streaming Import Progress Dialog
    importProgress?.let { progress ->
        ImportProgressDialog(progress = progress)
    }
}

@Composable
fun ImportProgressDialog(progress: ImportProgress) {
    AlertDialog(
        onDismissRequest = { /* Non-dismissible while streaming import to maintain consistency */ },
        confirmButton = {},
        title = {
            Text(
                text = "Importing Novel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = progress.bookTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                val fraction = if (progress.totalChapters > 0) {
                    (progress.currentChapter.toFloat() / progress.totalChapters.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Chapter ${progress.currentChapter} / ${progress.totalChapters}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${progress.chunksCreated} chunks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}
