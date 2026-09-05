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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.queue.ImportProgress
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import java.io.File

enum class Screen(val title: String) {
    HOME("Home"),
    LIBRARY("Library"),
    QUEUE("Queue"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    val pendingNotificationBookId = mutableStateOf<String?>(null)
    val pendingNotificationChapterId = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bId = intent?.getStringExtra("extra_open_book_id")
        val cId = intent?.getStringExtra("extra_open_chapter_id")
        if (bId != null && cId != null) {
            pendingNotificationBookId.value = bId
            pendingNotificationChapterId.value = cId
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
                    pendingNotificationChapterId = pendingNotificationChapterId
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val bId = intent.getStringExtra("extra_open_book_id")
        val cId = intent.getStringExtra("extra_open_chapter_id")
        if (bId != null && cId != null) {
            pendingNotificationBookId.value = bId
            pendingNotificationChapterId.value = cId
        }
    }
}

@Composable
fun MainApp(
    viewModel: MainViewModel,
    pendingNotificationBookId: MutableState<String?>? = null,
    pendingNotificationChapterId: MutableState<String?>? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? TranslatorApplication
    val settingsRepo = remember { app?.settingsRepository ?: com.example.data.repository.SettingsRepository(context) }
    val ttsManager = remember { app?.ttsManager }

    val initialBookId = remember {
        val intentBook = pendingNotificationBookId?.value
            ?: (context as? ComponentActivity)?.intent?.getStringExtra("extra_open_book_id")
        val activeTtsBook = ttsManager?.mediaMetadata?.value?.bookId?.takeIf { it.isNotBlank() }
        val sessionState = if (settingsRepo.isTtsAutoResumePlaybackEnabled()) settingsRepo.getTtsSessionState() else null
        val autoResumeBook = sessionState?.bookId?.takeIf { it.isNotBlank() }
        val lastBook = settingsRepo.getLastActiveBookId()
        intentBook ?: activeTtsBook ?: autoResumeBook ?: lastBook
    }

    val initialChapterId = remember {
        val intentChapter = pendingNotificationChapterId?.value
            ?: (context as? ComponentActivity)?.intent?.getStringExtra("extra_open_chapter_id")
        val activeTtsChapter = ttsManager?.mediaMetadata?.value?.chapterId?.takeIf { it.isNotBlank() }
        val sessionState = if (settingsRepo.isTtsAutoResumePlaybackEnabled()) settingsRepo.getTtsSessionState() else null
        val autoResumeChapter = sessionState?.chapterId?.takeIf { it.isNotBlank() }
        val targetBook = initialBookId
        val lastChapter = targetBook?.let { settingsRepo.getLastReadChapterId(it) }
        intentChapter ?: activeTtsChapter ?: autoResumeChapter ?: lastChapter
    }

    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedBookIdForDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var activeReaderBookId by rememberSaveable { mutableStateOf(initialBookId) }
    var activeReaderChapterId by rememberSaveable { mutableStateOf(initialChapterId) }

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
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
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
                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
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
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSaveSettings = { viewModel.updateSettings(it) },
                        onCheckForUpdates = { viewModel.checkForUpdates(silent = false) },
                        viewModel = viewModel
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
    updateState?.let { releaseInfo ->
        UpdateDialog(
            releaseInfo = releaseInfo,
            downloadProgress = updateProgress,
            onDismiss = { viewModel.dismissUpdateDialog() },
            onDownload = { downloadUrl -> viewModel.startApkDownload(downloadUrl) }
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
