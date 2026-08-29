package com.example.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderTheme(val label: String, val bg: Color, val text: Color) {
    SYSTEM("Default", Color.Transparent, Color.Unspecified),
    LIGHT("Light", Color(0xFFFBFBFB), Color(0xFF1E1E1E)),
    SEPIA("Sepia", Color(0xFFF4ECD8), Color(0xFF433422)),
    DARK("Dark", Color(0xFF121212), Color(0xFFE0E0E0))
}

@OptIn(ExperimentalMaterial3Api::class)
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

    var currentChapterId by remember { mutableStateOf(initialChapterId) }
    val chapters by db.chapterDao().observeChaptersByBook(bookId).collectAsState(initial = emptyList())
    val currentChapter = chapters.firstOrNull { it.id == currentChapterId }

    val currentChapterIndex = chapters.indexOfFirst { it.id == currentChapterId }
    val prevChapter = if (currentChapterIndex > 0) chapters[currentChapterIndex - 1] else null
    val nextChapter = if (currentChapterIndex >= 0 && currentChapterIndex < chapters.size - 1) chapters[currentChapterIndex + 1] else null

    var chapterTitle by remember { mutableStateOf("") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Reader Customization State
    var fontSize by remember { mutableFloatStateOf(17f) }
    var lineSpacingMultiplier by remember { mutableFloatStateOf(1.5f) }
    var selectedTheme by remember { mutableStateOf(ReaderTheme.SYSTEM) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChapterPickerSheet by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Load translated chapter content whenever currentChapterId changes
    LaunchedEffect(currentChapterId) {
        isLoading = true
        scrollState.scrollTo(0)
        viewModel.setLastReadChapterId(bookId, currentChapterId)

        withContext(Dispatchers.IO) {
            val job = db.jobDao().getJobByBookId(bookId)
            val chapter = db.chapterDao().getChapterById(currentChapterId)

            if (chapter != null) {
                val chunks = if (job != null) {
                    db.chunkDao().getChunksByJobAndChapter(job.id, currentChapterId)
                } else {
                    db.chunkDao().getChunksByChapter(bookId, currentChapterId)
                }

                val titleChunk = chunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }
                chapterTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() } ?: chapter.title

                val bodyChunks = chunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
                if (bodyChunks.isNotEmpty()) {
                    val extractedParagraphs = mutableListOf<String>()
                    for (chunk in bodyChunks) {
                        val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: chunk.sourceText
                        // Split into natural clean paragraphs
                        val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
                        for (p in rawParas) {
                            val clean = p.replace(Regex("<[^>]+>"), "").trim()
                            if (clean.isNotBlank()) {
                                extractedParagraphs.add(clean)
                            }
                        }
                    }
                    paragraphs = extractedParagraphs
                } else {
                    // Fallback to chapter title
                    paragraphs = listOf("This chapter is queued for translation or empty.")
                }
            }
        }
        isLoading = false
    }

    val contentBgColor = if (selectedTheme == ReaderTheme.SYSTEM) MaterialTheme.colorScheme.background else selectedTheme.bg
    val contentTextColor = if (selectedTheme == ReaderTheme.SYSTEM) MaterialTheme.colorScheme.onBackground else selectedTheme.text

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = chapterTitle.ifBlank { "Chapter ${currentChapterIndex + 1}" },
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy((fontSize * (lineSpacingMultiplier - 0.7f)).dp)
                ) {
                    // Chapter Title Header
                    Text(
                        text = chapterTitle,
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

                    // Paragraphs
                    paragraphs.forEachIndexed { index, paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineSpacingMultiplier).sp,
                                fontFamily = FontFamily.Serif
                            ),
                            color = contentTextColor,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Chapter End Navigation Prompts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (prevChapter != null) {
                            OutlinedButton(
                                onClick = { currentChapterId = prevChapter.id }
                            ) {
                                Text("← Previous Chapter")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        if (nextChapter != null) {
                            Button(
                                onClick = { currentChapterId = nextChapter.id }
                            ) {
                                Text("Next Chapter →")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
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
                Text(
                    text = "Table of Contents (${chapters.size} Chapters)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(chapters, key = { it.id }) { chapter ->
                        val isCurrent = chapter.id == currentChapterId
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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

                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

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
}
