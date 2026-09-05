package com.example.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.data.repository.SettingsRepository
import com.example.TranslatorApplication
import com.example.tts.ReaderTtsManager
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsState
import com.example.tts.TtsVoiceInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import java.io.File
import com.example.epub.EpubParser
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderTheme(val label: String, val bg: Color, val text: Color) {
    SYSTEM("Default", Color.Transparent, Color.Unspecified),
    LIGHT("Light", Color(0xFFFBFBFB), Color(0xFF1E1E1E)),
    SEPIA("Sepia", Color(0xFFF4ECD8), Color(0xFF433422)),
    DARK("Dark", Color(0xFF121212), Color(0xFFE0E0E0))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    initialChapterId: String,
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val settingsRepo = remember { SettingsRepository(context) }

    val app = context.applicationContext as? TranslatorApplication
    val ttsManager = remember { app?.ttsManager ?: ReaderTtsManager(context.applicationContext) }

    var currentChapterId by rememberSaveable(bookId) { mutableStateOf(initialChapterId) }

    val listState = rememberLazyListState()
    val tocListState = rememberLazyListState()

    // Lifecycle observer to persist position on background and destroy without killing background audio
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ttsManager, currentChapterId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    val meta = ttsManager.mediaMetadata.value
                    val isTtsForThis = (ttsManager.ttsState.value == TtsState.PLAYING || ttsManager.ttsState.value == TtsState.PAUSED) &&
                            meta.bookId == bookId && meta.chapterId == currentChapterId
                    val paraToSave = if (isTtsForThis) {
                        ttsManager.currentParagraphIndex.value
                    } else {
                        (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    }
                    viewModel.setLastReadParagraphIndex(bookId, currentChapterId, paraToSave)
                    settingsRepo.setLastActiveBookId(bookId)
                    ttsManager.onAppBackgrounded()
                }
                Lifecycle.Event.ON_RESUME -> {
                    ttsManager.onAppForegrounded()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    val meta = ttsManager.mediaMetadata.value
                    val isTtsForThis = (ttsManager.ttsState.value == TtsState.PLAYING || ttsManager.ttsState.value == TtsState.PAUSED) &&
                            meta.bookId == bookId && meta.chapterId == currentChapterId
                    val paraToSave = if (isTtsForThis) {
                        ttsManager.currentParagraphIndex.value
                    } else {
                        (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    }
                    viewModel.setLastReadParagraphIndex(bookId, currentChapterId, paraToSave)
                    // Note: Background playback is preserved; do NOT release ttsManager here.
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isTtsEnabled by ttsManager.isTtsEnabled.collectAsState()
    val ttsState by ttsManager.ttsState.collectAsState()
    val currentTtsParaIndex by ttsManager.currentParagraphIndex.collectAsState()
    val speechRate by ttsManager.speechRate.collectAsState()
    val availableVoices by ttsManager.availableVoices.collectAsState()
    val selectedVoice by ttsManager.selectedVoice.collectAsState()
    val ttsErrorMessage by ttsManager.errorMessage.collectAsState()
    val ttsMetadata by ttsManager.mediaMetadata.collectAsState()
    val autoAdvanceChapter by ttsManager.autoAdvanceChapter.collectAsState()
    val isCurrentChapterBookmarked by viewModel.observeIsChapterBookmarked(bookId, currentChapterId).collectAsState(initial = false)

    val isCurrentChapterActiveInTts = (ttsState == TtsState.PLAYING || ttsState == TtsState.PAUSED) &&
            ttsMetadata.bookId == bookId && ttsMetadata.chapterId == currentChapterId

    val isAnotherNovelOrChapterPlaying = (ttsState == TtsState.PLAYING || ttsState == TtsState.PAUSED) &&
            (ttsMetadata.bookId != bookId || ttsMetadata.chapterId != currentChapterId)

    // Synchronize currentChapterId if TTS is actively playing or paused for this book and advances in background
    LaunchedEffect(ttsMetadata.chapterId, ttsMetadata.bookId, ttsState) {
        if ((ttsState == TtsState.PLAYING || ttsState == TtsState.PAUSED) &&
            ttsMetadata.bookId == bookId &&
            ttsMetadata.chapterId.isNotBlank() &&
            ttsMetadata.chapterId != currentChapterId
        ) {
            currentChapterId = ttsMetadata.chapterId
        }
    }

    // Restore persisted settings into TTS manager on first composition
    LaunchedEffect(Unit) {
        ttsManager.setTtsEnabled(settingsRepo.isTtsEnabled())
        ttsManager.setSpeechRate(settingsRepo.getTtsSpeechRate())
        val savedVoice = settingsRepo.getTtsVoiceId()
        if (savedVoice != null) {
            ttsManager.savedVoiceId = savedVoice
            ttsManager.selectVoiceById(savedVoice)
        }
    }

    var showTtsControls by rememberSaveable { mutableStateOf(false) }
    var showVoiceSelectionSheet by remember { mutableStateOf(false) }
    var showTtsRulesDialog by remember { mutableStateOf(false) }
    var shouldContinueTtsOnNextChapter by remember { mutableStateOf(false) }
    val ttsRules by viewModel.ttsRules.collectAsState()

    val chapters by db.chapterDao().observeChaptersByBook(bookId).collectAsState(initial = emptyList())
    val currentChapter = chapters.firstOrNull { it.id == currentChapterId }

    val currentChapterIndex = chapters.indexOfFirst { it.id == currentChapterId }
    val prevChapter = if (currentChapterIndex > 0) chapters[currentChapterIndex - 1] else null
    val nextChapter = if (currentChapterIndex >= 0 && currentChapterIndex < chapters.size - 1) chapters[currentChapterIndex + 1] else null

    var chapterTitle by remember { mutableStateOf("") }
    var chapterTitlesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var novelTitle by remember { mutableStateOf("") }

    // Synchronize if parent initialChapterId changes
    LaunchedEffect(initialChapterId) {
        if (initialChapterId.isNotBlank() && initialChapterId != currentChapterId) {
            currentChapterId = initialChapterId
        }
    }

    // Auto-scroll to active TTS paragraph during playback and persist reading position
    LaunchedEffect(currentTtsParaIndex, ttsState, isCurrentChapterActiveInTts) {
        if (isCurrentChapterActiveInTts && ttsState == TtsState.PLAYING && currentTtsParaIndex in paragraphs.indices) {
            listState.animateScrollToItem((currentTtsParaIndex + 1).coerceAtMost(paragraphs.size))
            viewModel.setLastReadParagraphIndex(bookId, currentChapterId, currentTtsParaIndex)
        }
    }

    // Load translated chapter titles map and novel title for TOC and header
    LaunchedEffect(bookId) {
        withContext(Dispatchers.IO) {
            val book = db.bookDao().getBookById(bookId)
            val titleChunk = db.chunkDao().getTitleChunkByBook(bookId)
            novelTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() } ?: book?.title ?: ""

            val titleChunks = db.chunkDao().getChapterTitlesByBook(bookId)
            chapterTitlesMap = titleChunks.associate { it.chapterId to (it.translatedText ?: "") }
        }
    }

    // Reader Customization State - preserved across configuration changes
    var fontSize by rememberSaveable { mutableFloatStateOf(17f) }
    var lineSpacingMultiplier by rememberSaveable { mutableFloatStateOf(1.5f) }
    var selectedTheme by rememberSaveable { mutableStateOf(ReaderTheme.SYSTEM) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChapterPickerSheet by remember { mutableStateOf(false) }
    var isAutoResumeTriggered by rememberSaveable { mutableStateOf(false) }
    var lastSavedParaIndex by rememberSaveable { mutableIntStateOf(0) }

    val playCurrentChapter: (Int) -> Unit = { targetIndex ->
        ttsManager.setChapterAndParagraphs(
            chapterId = currentChapterId,
            newParagraphs = paragraphs,
            continuePlaying = true,
            startIndex = targetIndex,
            bookId = bookId,
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            chapterOrder = currentChapter?.chapterOrder ?: 0
        )
    }

    // Load translated chapter content whenever currentChapterId changes
    LaunchedEffect(currentChapterId) {
        isLoading = true
        viewModel.setLastReadChapterId(bookId, currentChapterId)
        val savedPara = viewModel.getLastReadParagraphIndex(bookId, currentChapterId)
        lastSavedParaIndex = savedPara

        withContext(Dispatchers.IO) {
            val book = db.bookDao().getBookById(bookId)
            val job = db.jobDao().getJobByBookId(bookId)
            val chapter = db.chapterDao().getChapterById(currentChapterId)

            if (chapter != null) {
                if (book?.isLocalBook == true || (job == null && book?.localFilePath != null)) {
                    chapterTitle = if (chapter.title.isNotBlank()) chapter.title else "Chapter ${chapter.chapterOrder + 1}"
                    val bookDir = File(context.filesDir, "books/$bookId")
                    val epubFile = book?.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                        ?: File(bookDir, "source.epub")
                    if (epubFile.exists() && epubFile.length() > 0L) {
                        val extractedParas = EpubParser.extractChapterParagraphs(epubFile, chapter.originalHref)
                        if (extractedParas.isNotEmpty()) {
                            paragraphs = extractedParas
                        } else {
                            paragraphs = listOf("This chapter is empty.")
                        }
                    } else {
                        paragraphs = listOf("Source EPUB file not found.")
                    }
                } else {
                    val chunks = if (job != null) {
                        db.chunkDao().getChunksByJobAndChapter(job.id, currentChapterId)
                    } else {
                        db.chunkDao().getChunksByChapter(bookId, currentChapterId)
                    }

                    val titleChunk = chunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }
                    val resolvedTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() }
                        ?: chapterTitlesMap[currentChapterId]?.takeIf { it.isNotBlank() }
                        ?: if (chapter.title.any { it.code in 0x4e00..0x9fff }) "Chapter ${chapter.chapterOrder + 1}" else chapter.title
                    chapterTitle = resolvedTitle

                    val bodyChunks = chunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
                    if (bodyChunks.isNotEmpty()) {
                        val extractedParagraphs = mutableListOf<String>()
                        for (chunk in bodyChunks) {
                            // Strict requirement: Only translated English text is displayed. Never fall back to Chinese source text.
                            val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: continue
                            val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
                            for (p in rawParas) {
                                val clean = p.replace(Regex("<[^>]+>"), "").trim()
                                if (clean.isNotBlank()) {
                                    extractedParagraphs.add(clean)
                                }
                            }
                        }
                        if (extractedParagraphs.isNotEmpty()) {
                            paragraphs = extractedParagraphs
                        } else {
                            paragraphs = listOf("This chapter has not been translated yet. Please wait for translation to complete.")
                        }
                    } else {
                        paragraphs = listOf("This chapter has not been translated yet. Please wait for translation to complete.")
                    }
                }
            }
        }
        isLoading = false

        val resumeTts = shouldContinueTtsOnNextChapter
        shouldContinueTtsOnNextChapter = false

        if (isCurrentChapterActiveInTts) {
            ttsManager.setChapterMetadata(
                bookId = bookId,
                chapterId = currentChapterId,
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                chapterOrder = currentChapter?.chapterOrder ?: 0
            )
            val activePara = ttsManager.currentParagraphIndex.value
            if (activePara in paragraphs.indices) {
                listState.scrollToItem((activePara + 1).coerceAtMost(paragraphs.size))
            }
        } else if (!isAnotherNovelOrChapterPlaying) {
            val startPara = if (resumeTts) 0 else savedPara
            ttsManager.setChapterAndParagraphs(
                chapterId = currentChapterId,
                newParagraphs = paragraphs,
                continuePlaying = resumeTts,
                startIndex = startPara,
                bookId = bookId,
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                chapterOrder = currentChapter?.chapterOrder ?: 0
            )
            if (!resumeTts && savedPara > 0 && savedPara < paragraphs.size) {
                listState.scrollToItem((savedPara + 1).coerceAtMost(paragraphs.size))
            } else if (!resumeTts) {
                listState.scrollToItem(0)
            }
        } else {
            // Another novel or chapter is playing in the background:
            // Do NOT touch ttsManager so background playback is preserved.
            if (savedPara > 0 && savedPara < paragraphs.size) {
                listState.scrollToItem((savedPara + 1).coerceAtMost(paragraphs.size))
            } else {
                listState.scrollToItem(0)
            }
        }

        // Optional: Resume TTS automatically if explicitly enabled and previous session was PLAYING
        if (!isAutoResumeTriggered && settingsRepo.isTtsAutoResumePlaybackEnabled()) {
            isAutoResumeTriggered = true
            val session = settingsRepo.getTtsSessionState()
            if (session != null && session.bookId == bookId && session.chapterId == currentChapterId && session.playbackState == TtsState.PLAYING.name && (ttsState == TtsState.IDLE || ttsState == TtsState.STOPPED)) {
                val targetPara = session.paragraphIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                playCurrentChapter(targetPara)
            }
        }
    }

    // Persist reading paragraph position
    LaunchedEffect(listState, currentChapterId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { itemIndex ->
                if (!isLoading && paragraphs.isNotEmpty()) {
                    val paraIndex = (itemIndex - 1).coerceAtLeast(0)
                    viewModel.setLastReadParagraphIndex(bookId, currentChapterId, paraIndex)
                }
            }
    }

    LaunchedEffect(showChapterPickerSheet) {
        if (showChapterPickerSheet && currentChapterIndex >= 0) {
            tocListState.scrollToItem((currentChapterIndex - 2).coerceAtLeast(0))
        }
    }

    val displayHeaderTitle = chapterTitlesMap[currentChapterId]?.takeIf { it.isNotBlank() }
        ?: chapterTitle.takeIf { it.isNotBlank() }
        ?: currentChapter?.let { if (it.title.any { c -> c.code in 0x4e00..0x9fff }) "Chapter ${it.chapterOrder + 1}" else it.title }
        ?: "Chapter ${currentChapterIndex + 1}"

    val contentBgColor = if (selectedTheme == ReaderTheme.SYSTEM) MaterialTheme.colorScheme.background else selectedTheme.bg
    val contentTextColor = if (selectedTheme == ReaderTheme.SYSTEM) MaterialTheme.colorScheme.onBackground else selectedTheme.text

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (novelTitle.isNotBlank()) {
                            Text(
                                text = novelTitle,
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = displayHeaderTitle,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = displayHeaderTitle,
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("reader_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isTtsEnabled) {
                                ttsManager.setTtsEnabled(true)
                                settingsRepo.setTtsEnabled(true)
                                showTtsControls = true
                            } else {
                                showTtsControls = !showTtsControls
                            }
                        },
                        modifier = Modifier.testTag("reader_tts_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (!isTtsEnabled) Icons.AutoMirrored.Filled.VolumeMute else if (showTtsControls || ttsState == TtsState.PLAYING) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeMute,
                            contentDescription = if (!isTtsEnabled) "Enable Text to Speech" else "Text to Speech Controls",
                            tint = if (!isTtsEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else if (ttsState == TtsState.PLAYING) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleBookmark(bookId, currentChapterId) },
                        modifier = Modifier.testTag("reader_bookmark_button")
                    ) {
                        Icon(
                            imageVector = if (isCurrentChapterBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isCurrentChapterBookmarked) "Remove Bookmark" else "Bookmark Chapter",
                            tint = if (isCurrentChapterBookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }

                    IconButton(
                        onClick = { showChapterPickerSheet = true },
                        modifier = Modifier.testTag("reader_toc_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters Table of Contents")
                    }

                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("reader_settings_button")
                    ) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Typography and Theme")
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = showTtsControls,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Status bar & voice/speed controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    val statusText = when {
                                        !isTtsEnabled -> "Text-to-Speech disabled"
                                        isAnotherNovelOrChapterPlaying -> {
                                            if (ttsMetadata.bookId != bookId) {
                                                "Playing in background: ${ttsMetadata.novelTitle.ifBlank { "Audiobook" }}"
                                            } else {
                                                "Playing Chapter ${(ttsMetadata.chapterOrder + 1)} in background"
                                            }
                                        }
                                        ttsState == TtsState.INITIALIZING -> "Connecting speech engine..."
                                        ttsState == TtsState.PLAYING -> "Reading paragraph ${currentTtsParaIndex + 1} of ${paragraphs.size}"
                                        ttsState == TtsState.PAUSED -> "Paused at paragraph ${currentTtsParaIndex + 1} of ${paragraphs.size}"
                                        ttsState == TtsState.STOPPED -> "Stopped"
                                        ttsState == TtsState.IDLE -> if (paragraphs.isNotEmpty()) "Ready to read (${paragraphs.size} paras)" else "No paragraphs to read"
                                        ttsState == TtsState.ERROR -> ttsErrorMessage ?: "Speech engine unavailable"
                                        else -> "Ready"
                                    }
                                    val statusColor = when {
                                        !isTtsEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        ttsState == TtsState.PLAYING -> MaterialTheme.colorScheme.primary
                                        ttsState == TtsState.PAUSED -> MaterialTheme.colorScheme.tertiary
                                        ttsState == TtsState.ERROR -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = statusColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // TTS Master Enable/Disable button
                                    IconButton(
                                        onClick = {
                                            val next = !isTtsEnabled
                                            ttsManager.setTtsEnabled(next)
                                            settingsRepo.setTtsEnabled(next)
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("reader_tts_master_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (isTtsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeMute,
                                            contentDescription = if (isTtsEnabled) "Disable Text to Speech" else "Enable Text to Speech",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }

                                    // Auto-advance chapter toggle button
                                    IconButton(
                                        onClick = {
                                            val next = !autoAdvanceChapter
                                            ttsManager.setAutoAdvanceChapter(next)
                                            settingsRepo.setTtsAutoAdvanceChapterEnabled(next)
                                        },
                                        enabled = isTtsEnabled,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("reader_tts_auto_advance_button")
                                    ) {
                                        Icon(
                                            imageVector = if (autoAdvanceChapter) Icons.Default.Repeat else Icons.Default.RepeatOne,
                                            contentDescription = if (autoAdvanceChapter) "Continuous Chapter Play: On" else "Continuous Chapter Play: Off",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (autoAdvanceChapter && isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }

                                    IconButton(
                                        onClick = { showVoiceSelectionSheet = true },
                                        enabled = isTtsEnabled && ttsState != TtsState.INITIALIZING,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("reader_tts_voice_button")
                                    ) {
                                        Icon(
                                            Icons.Default.RecordVoiceOver,
                                            contentDescription = "Select Voice",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (selectedVoice != null && isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }

                                    IconButton(
                                        onClick = { showTtsRulesDialog = true },
                                        enabled = isTtsEnabled,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("reader_tts_rules_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Spellcheck,
                                            contentDescription = "TTS Speech Rules",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }

                                    var showSpeedMenu by remember { mutableStateOf(false) }
                                    Box {
                                        FilledTonalButton(
                                            onClick = { showSpeedMenu = true },
                                            enabled = isTtsEnabled && ttsState != TtsState.INITIALIZING,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .testTag("reader_tts_speed_button")
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.2gx", speechRate),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showSpeedMenu,
                                            onDismissRequest = { showSpeedMenu = false }
                                        ) {
                                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f).forEach { rate ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = "${rate}x",
                                                            fontWeight = if (speechRate == rate) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        ttsManager.setSpeechRate(rate)
                                                        settingsRepo.setTtsSpeechRate(rate)
                                                        showSpeedMenu = false
                                                    },
                                                    trailingIcon = if (speechRate == rate) {
                                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                    } else null
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = { showTtsControls = false },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("reader_tts_close_button")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Hide TTS Controls", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            // Playback controls row: Prev, Play/Resume, Pause, Stop, Next
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Previous Paragraph
                                IconButton(
                                    onClick = { ttsManager.previousParagraph() },
                                    enabled = isTtsEnabled && isCurrentChapterActiveInTts && ttsState != TtsState.INITIALIZING && currentTtsParaIndex > 0 && paragraphs.isNotEmpty(),
                                    modifier = Modifier.testTag("reader_tts_prev_para_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SkipPrevious,
                                        contentDescription = "Previous Paragraph",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // 2. Play / Resume Button
                                val isPlayOrResumeEnabled = isTtsEnabled && ttsState != TtsState.INITIALIZING && paragraphs.isNotEmpty() && (!isCurrentChapterActiveInTts || ttsState != TtsState.PLAYING)

                                FilledIconButton(
                                    onClick = {
                                        if (isCurrentChapterActiveInTts) {
                                            if (ttsState == TtsState.PAUSED) {
                                                ttsManager.resume()
                                            } else {
                                                ttsManager.play(currentTtsParaIndex)
                                            }
                                        } else {
                                            val targetPara = if (currentTtsParaIndex in paragraphs.indices) currentTtsParaIndex else lastSavedParaIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                                            playCurrentChapter(targetPara)
                                        }
                                    },
                                    enabled = isPlayOrResumeEnabled,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag(if (isCurrentChapterActiveInTts && ttsState == TtsState.PAUSED) "reader_tts_resume_button" else "reader_tts_play_button")
                                        .testTag("reader_tts_play_pause_button"),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = if (isCurrentChapterActiveInTts && ttsState == TtsState.PAUSED) "Resume" else "Play",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // 3. Pause Button
                                val isPauseEnabled = isTtsEnabled && ttsState == TtsState.PLAYING
                                FilledIconButton(
                                    onClick = { ttsManager.pause() },
                                    enabled = isPauseEnabled,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("reader_tts_pause_button"),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Pause",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // 4. Stop Button
                                IconButton(
                                    onClick = { ttsManager.stop() },
                                    enabled = isTtsEnabled && (ttsState == TtsState.PLAYING || ttsState == TtsState.PAUSED),
                                    modifier = Modifier.testTag("reader_tts_stop_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // 5. Next Paragraph
                                IconButton(
                                    onClick = { ttsManager.nextParagraph() },
                                    enabled = isTtsEnabled && isCurrentChapterActiveInTts && ttsState != TtsState.INITIALIZING && currentTtsParaIndex < paragraphs.size - 1 && paragraphs.isNotEmpty(),
                                    modifier = Modifier.testTag("reader_tts_next_para_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SkipNext,
                                        contentDescription = "Next Paragraph",
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (prevChapter != null) {
                                    if (isCurrentChapterActiveInTts && ttsState == TtsState.PLAYING) {
                                        shouldContinueTtsOnNextChapter = true
                                    }
                                    currentChapterId = prevChapter.id
                                }
                            },
                            enabled = prevChapter != null,
                            modifier = Modifier.testTag("reader_prev_chapter_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Prev")
                        }

                        Text(
                            text = "${currentChapterIndex + 1} / ${chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FilledTonalButton(
                            onClick = {
                                if (nextChapter != null) {
                                    if (isCurrentChapterActiveInTts && ttsState == TtsState.PLAYING) {
                                        shouldContinueTtsOnNextChapter = true
                                    }
                                    currentChapterId = nextChapter.id
                                }
                            },
                            enabled = nextChapter != null,
                            modifier = Modifier.testTag("reader_next_chapter_button")
                        ) {
                            Text("Next")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(contentBgColor)
                .padding(paddingValues)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy((fontSize * (lineSpacingMultiplier - 0.7f)).coerceAtLeast(8f).dp)
                ) {
                    // Chapter Title Header
                    item(key = "chapter_header") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = displayHeaderTitle,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = (fontSize + 5).sp,
                                    lineHeight = ((fontSize + 5) * 1.3f).sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = contentTextColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            )
                            HorizontalDivider(color = contentTextColor.copy(alpha = 0.2f))
                        }
                    }

                    // Paragraphs
                    itemsIndexed(
                        items = paragraphs,
                        key = { index, _ -> "ch_${currentChapterId}_para_$index" }
                    ) { index, paragraph ->
                        val isTtsActive = isCurrentChapterActiveInTts && index == currentTtsParaIndex
                        val shape = RoundedCornerShape(8.dp)

                        val paraModifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (isTtsActive) {
                                    Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                } else {
                                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                }
                            )
                            .combinedClickable(
                                onDoubleClick = {
                                    if (isTtsEnabled) {
                                        if (isCurrentChapterActiveInTts) {
                                            ttsManager.play(index)
                                        } else {
                                            playCurrentChapter(index)
                                        }
                                        showTtsControls = true
                                    }
                                },
                                onClick = {
                                    if (isTtsEnabled && (showTtsControls || ttsState == TtsState.PLAYING || ttsState == TtsState.PAUSED)) {
                                        if (isCurrentChapterActiveInTts) {
                                            ttsManager.play(index)
                                        } else {
                                            playCurrentChapter(index)
                                        }
                                    }
                                }
                            )
                            .testTag("reader_paragraph_$index")

                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineSpacingMultiplier).sp,
                                fontFamily = FontFamily.Serif
                            ),
                            color = if (isTtsActive && selectedTheme == ReaderTheme.SYSTEM) MaterialTheme.colorScheme.onPrimaryContainer else contentTextColor,
                            textAlign = TextAlign.Start,
                            modifier = paraModifier
                        )
                    }
                }
            }
        }
    }

    // Reader Settings Bottom Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "Reader Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Font Size Control
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.bodyMedium)
                        Text("${fontSize.toInt()} sp", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 13f..28f,
                        steps = 14
                    )
                }

                // Line Spacing Control
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Line Spacing", style = MaterialTheme.typography.bodyMedium)
                        Text(String.format("%.1fx", lineSpacingMultiplier), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = lineSpacingMultiplier,
                        onValueChange = { lineSpacingMultiplier = it },
                        valueRange = 1.2f..2.2f,
                        steps = 9
                    )
                }

                // Color Themes
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ReaderTheme.values().forEach { theme ->
                            FilterChip(
                                selected = selectedTheme == theme,
                                onClick = { selectedTheme = theme },
                                label = { Text(theme.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Chapter Picker / Table of Contents Bottom Sheet
    if (showChapterPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChapterPickerSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (novelTitle.isNotBlank()) {
                    Text(
                        text = novelTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Table of Contents (${chapters.size} Chapters)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    state = tocListState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(chapters, key = { it.id }) { chapter ->
                        val isCurrent = chapter.id == currentChapterId
                        val trTitle = chapterTitlesMap[chapter.id]?.takeIf { it.isNotBlank() }
                        val displayChapterTitle = trTitle
                            ?: if (chapter.title.any { it.code in 0x4e00..0x9fff }) "Chapter ${chapter.chapterOrder + 1}" else chapter.title

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (ttsState == TtsState.PLAYING) {
                                        shouldContinueTtsOnNextChapter = true
                                    }
                                    currentChapterId = chapter.id
                                    showChapterPickerSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "${chapter.chapterOrder + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = displayChapterTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )

                                    if (trTitle != null && trTitle != chapter.title && !chapter.title.any { it.code in 0x4e00..0x9fff }) {
                                        Text(
                                            text = chapter.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Current",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Voice Selection Bottom Sheet
    if (showVoiceSelectionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSelectionSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Select TTS Voice",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Installed system voices for reading novel chapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (availableVoices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No additional voice profiles exposed by Android TTS engine. The default system voice is being used.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(availableVoices, key = { it.id }) { voiceInfo ->
                            val isSelected = selectedVoice?.id == voiceInfo.id
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ttsManager.selectVoice(voiceInfo)
                                        settingsRepo.setTtsVoiceId(voiceInfo.id)
                                        showVoiceSelectionSheet = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voiceInfo.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (voiceInfo.isNetworkRequired) "Online voice" else "Device offline voice",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTtsRulesDialog) {
        TtsRulesDialog(
            rules = ttsRules,
            currentBookId = bookId,
            onDismiss = { showTtsRulesDialog = false },
            onSaveRule = { viewModel.saveTtsRule(it) },
            onDeleteRule = { viewModel.deleteTtsRule(it) },
            onToggleRule = { viewModel.toggleTtsRule(it) },
            onReorderRule = { id, up -> viewModel.reorderTtsRule(id, up) }
        )
    }
}
