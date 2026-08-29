package com.example

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import java.io.File

enum class Screen(val title: String) {
    HOME("Home"),
    QUEUE("Queue"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = settings.isDarkMode) {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedBookIdForDetail by remember { mutableStateOf<String?>(null) }
    var activeReaderChapter by remember { mutableStateOf<Pair<String, String>?>(null) } // bookId to chapterId

    val snackbarHostState = remember { SnackbarHostState() }

    val booksWithJobs by viewModel.allBooksWithJobs.collectAsStateWithLifecycle()
    val activeWorkers by viewModel.activeWorkers.collectAsStateWithLifecycle()
    val activeWorkersByJob by viewModel.activeWorkersByJob.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val exportingBookIds by viewModel.exportingBookIds.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
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

    if (activeReaderChapter != null) {
        val (bId, chId) = activeReaderChapter!!
        ReaderScreen(
            bookId = bId,
            initialChapterId = chId,
            viewModel = viewModel,
            onNavigateBack = { activeReaderChapter = null }
        )
    } else if (selectedBookIdForDetail != null) {
        NovelDetailScreen(
            bookId = selectedBookIdForDetail!!,
            viewModel = viewModel,
            onNavigateBack = { selectedBookIdForDetail = null },
            onOpenReader = { chId ->
                activeReaderChapter = Pair(selectedBookIdForDetail!!, chId)
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                    NavigationBarItem(
                        selected = currentScreen == Screen.HOME,
                        onClick = { currentScreen = Screen.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        modifier = Modifier.testTag("nav_home")
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.QUEUE,
                        onClick = { currentScreen = Screen.QUEUE },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val activeCount = booksWithJobs.count { it.job?.status == "TRANSLATING" || it.job?.status == "QUEUED" }
                                    if (activeCount > 0) {
                                        Badge { Text("$activeCount") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ListAlt, contentDescription = "Queue")
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
                        onSelectSingleEpub = { viewModel.previewSingleEpub(it) },
                        onSelectMultipleEpubs = { viewModel.importMultipleEpubs(it) },
                        onNavigateToQueue = { currentScreen = Screen.QUEUE },
                        onOpenNovelDetail = { bookId -> selectedBookIdForDetail = bookId },
                        onExportEpub = onExport,
                        onPauseJob = { viewModel.pauseJob(it) },
                        onResumeJob = { viewModel.resumeJob(it) },
                        onRetryJob = { viewModel.retryFailed(it) },
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
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSaveSettings = { viewModel.updateSettings(it) },
                        onCheckForUpdates = { viewModel.checkForUpdates(silent = false) }
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
            onConfirm = { viewModel.enqueuePreviewedBook() }
        )
    }

    // In-App Update Dialog
    updateState?.let { releaseInfo ->
        UpdateDialog(
            releaseInfo = releaseInfo,
            downloadProgress = updateProgress,
            onDismiss = { viewModel.dismissUpdateDialog() },
            onDownload = { downloadUrl -> viewModel.startApkDownload(downloadUrl) }
        )
    }
}
