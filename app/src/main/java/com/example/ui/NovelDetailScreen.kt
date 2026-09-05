package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    bookId: String,
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onOpenReader: (chapterId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    // Observe book and latest job
    val booksWithJob by viewModel.allBooksWithJobs.collectAsState()
    val currentBookJob = booksWithJob.firstOrNull { it.book.id == bookId }
    val exportingBookIds by viewModel.exportingBookIds.collectAsState()
    val isExporting = exportingBookIds.contains(bookId)

    // Load chapters and chapter translation chunks
    val chapters by db.chapterDao().observeChaptersByBook(bookId).collectAsState(initial = emptyList())
    
    // Load metadata chunks (translated title and description)
    var translatedTitle by remember { mutableStateOf<String?>(null) }
    var translatedDescription by remember { mutableStateOf<String?>(null) }
    var chapterProgressMap by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) } // chapterId -> (completed, total)
    var chapterTitlesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var lastReadChapterId by remember(bookId) { mutableStateOf(viewModel.getLastReadChapterId(bookId)) }

    LaunchedEffect(bookId) {
        lastReadChapterId = viewModel.getLastReadChapterId(bookId)
    }

    LaunchedEffect(bookId, currentBookJob?.job?.id) {
        withContext(Dispatchers.IO) {
            val titleChunk = db.chunkDao().getTitleChunkByBook(bookId)
            translatedTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() }

            val descChunk = db.chunkDao().getDescriptionChunkByBook(bookId)
            translatedDescription = descChunk?.translatedText?.takeIf { it.isNotBlank() }

            val titleChunks = db.chunkDao().getChapterTitlesByBook(bookId)
            chapterTitlesMap = titleChunks.associate { it.chapterId to (it.translatedText ?: "") }

            val progressList = db.chunkDao().getChapterBodyProgressByBook(bookId)
            chapterProgressMap = progressList.associate { it.chapterId to Pair(it.completedChunks, it.totalChunks) }
        }
    }

    // Bookmarked chapters state
    val bookmarkedChapterIdsList by viewModel.observeBookmarkedChapterIds(bookId).collectAsState(emptyList())
    val bookmarkedChapterIds = remember(bookmarkedChapterIdsList) { bookmarkedChapterIdsList.toSet() }
    var showOnlyBookmarks by rememberSaveable(bookId) { mutableStateOf(false) }

    // Expandable description state
    var isDescriptionExpanded by rememberSaveable(bookId) { mutableStateOf(false) }

    // Selection mode state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedChapterIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Expandable Grouping by 100
    var expandedGroupIndices by rememberSaveable {
        val initialSet = mutableSetOf<Int>()
        val targetIndex = if (lastReadChapterId != null) {
            val ch = chapters.firstOrNull { it.id == lastReadChapterId }
            if (ch != null) ch.chapterOrder / 100 else 0
        } else 0
        initialSet.add(targetIndex)
        mutableStateOf(initialSet.toSet())
    }

    LaunchedEffect(chapters, lastReadChapterId) {
        if (chapters.isNotEmpty()) {
            val targetIndex = if (lastReadChapterId != null) {
                val ch = chapters.firstOrNull { it.id == lastReadChapterId }
                if (ch != null) ch.chapterOrder / 100 else 0
            } else 0
            if (!expandedGroupIndices.contains(targetIndex)) {
                expandedGroupIndices = expandedGroupIndices + targetIndex
            }
        }
    }

    val displayTitle = translatedTitle ?: currentBookJob?.book?.title ?: "Novel Details"
    val originalTitle = currentBookJob?.book?.title
    val author = currentBookJob?.book?.author ?: "Unknown"
    val displayDesc = translatedDescription ?: currentBookJob?.book?.description ?: "No description available."
    val coverPath = currentBookJob?.book?.coverPath

    val isLocalBook = currentBookJob?.book?.isLocalBook == true || currentBookJob?.job == null

    val totalChapters = chapters.size
    val translatedChaptersCount = if (isLocalBook) totalChapters else chapters.count { chapter ->
        val prog = chapterProgressMap[chapter.id]
        prog != null && prog.second > 0 && prog.first >= prog.second
    }

    // Grouping by 100 chapters
    val chapterGroups = remember(chapters) {
        if (chapters.isEmpty()) emptyList()
        else {
            chapters.chunked(100).mapIndexed { index, groupList ->
                val startNum = groupList.first().chapterOrder + 1
                val endNum = groupList.last().chapterOrder + 1
                ChapterGroup(
                    groupIndex = index,
                    startChapterNum = startNum,
                    endChapterNum = endNum,
                    chapters = groupList
                )
            }
        }
    }

    // Memoize translated counts per chapter group to avoid O(N) calculations during scrolling/recomposition
    val groupProgressCounts = remember(chapterGroups, chapterProgressMap, isLocalBook) {
        chapterGroups.associate { group ->
            group.groupIndex to if (isLocalBook) group.chapters.size else group.chapters.count { ch ->
                val prog = chapterProgressMap[ch.id]
                prog != null && prog.second > 0 && prog.first >= prog.second
            }
        }
    }

    // SAF File Creator Launcher for Selected / Range Export
    var pendingExportTitle by remember { mutableStateOf<String?>(null) }
    var pendingExportChapterIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri: Uri? ->
        if (uri != null && pendingExportChapterIds.isNotEmpty()) {
            viewModel.exportCustomChapters(
                context = context,
                bookId = bookId,
                selectedChapterIds = pendingExportChapterIds,
                customTitle = pendingExportTitle,
                destinationUri = uri
            )
        }
    }

    val fullExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportEnglishEpub(context, bookId, uri)
        }
    }

    var savedScrollIndex by rememberSaveable(bookId) { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable(bookId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollIndex,
        initialFirstVisibleItemScrollOffset = savedScrollOffset
    )

    // Restore scroll position as soon as asynchronous chapter list is populated
    LaunchedEffect(chapters.isNotEmpty()) {
        if (chapters.isNotEmpty() && savedScrollIndex > 0) {
            listState.scrollToItem(savedScrollIndex, savedScrollOffset)
        }
    }

    // Persist scroll position across compositions and back navigation
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                if (chapters.isNotEmpty() && (index > 0 || offset > 0)) {
                    savedScrollIndex = index
                    savedScrollOffset = offset
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isSelectionMode) "${selectedChapterIds.size} Selected" else displayTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectedChapterIds = emptySet()
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("novel_detail_back_button")
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = {
                                if (selectedChapterIds.size == chapters.size) {
                                    selectedChapterIds = emptySet()
                                } else {
                                    selectedChapterIds = chapters.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedChapterIds.size == chapters.size) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = "Toggle Select All"
                            )
                        }

                        IconButton(
                            onClick = { showRangeDialog = true },
                            modifier = Modifier.testTag("select_range_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Select Range"
                            )
                        }

                        if (selectedChapterIds.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val sortedSelected = chapters.filter { selectedChapterIds.contains(it.id) }.sortedBy { it.chapterOrder }
                                    val cleanBookTitle = displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Novel" }
                                    val (exportTitle, defaultFilename) = if (sortedSelected.size == chapters.size && chapters.isNotEmpty()) {
                                        cleanBookTitle to "$cleanBookTitle.epub"
                                    } else if (sortedSelected.isNotEmpty()) {
                                        val startNum = sortedSelected.first().chapterOrder + 1
                                        val endNum = sortedSelected.last().chapterOrder + 1
                                        val rangeStr = if (startNum == endNum) "Chapter $startNum" else "Chapter $startNum-$endNum"
                                        "$cleanBookTitle $rangeStr" to "$cleanBookTitle $rangeStr.epub"
                                    } else {
                                        cleanBookTitle to "$cleanBookTitle.epub"
                                    }
                                    pendingExportTitle = exportTitle
                                    pendingExportChapterIds = selectedChapterIds
                                    exportLauncher.launch(defaultFilename)
                                },
                                modifier = Modifier.testTag("export_selected_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "Export Selected"
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { isSelectionMode = true },
                            modifier = Modifier.testTag("enter_selection_mode_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = "Select Chapters"
                            )
                        }

                        IconButton(
                            onClick = {
                                val cleanBookTitle = displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Novel" }
                                val defaultFilename = "$cleanBookTitle.epub"
                                fullExportLauncher.launch(defaultFilename)
                            },
                            enabled = !isExporting,
                            modifier = Modifier.testTag("full_export_button")
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Export Full EPUB"
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isSelectionMode && chapters.isNotEmpty()) {
                val targetChapterId = lastReadChapterId ?: chapters.firstOrNull()?.id
                if (targetChapterId != null) {
                    ExtendedFloatingActionButton(
                        onClick = { onOpenReader(targetChapterId) },
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        text = { Text(if (lastReadChapterId != null) "Continue Reading" else "Start Reading") },
                        modifier = Modifier.testTag("read_fab")
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        val filteredChapters = remember(chapters, searchQuery, showOnlyBookmarks, bookmarkedChapterIds, chapterTitlesMap) {
            var list = chapters
            if (showOnlyBookmarks) {
                list = list.filter { bookmarkedChapterIds.contains(it.id) }
            }
            if (searchQuery.isNotBlank()) {
                list = list.filter { chapter ->
                    val trTitle = chapterTitlesMap[chapter.id] ?: ""
                    chapter.title.contains(searchQuery, ignoreCase = true) ||
                            trTitle.contains(searchQuery, ignoreCase = true) ||
                            (chapter.chapterOrder + 1).toString() == searchQuery.trim()
                }
            }
            list
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Info Card
            item(key = "header_info") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Cover Image
                            if (coverPath != null && File(coverPath).exists()) {
                                AsyncImage(
                                    model = File(coverPath),
                                    contentDescription = "Cover of $displayTitle",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Book,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            // Metadata Column
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = displayTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (translatedTitle.isNullOrBlank() && originalTitle != null && originalTitle != displayTitle) {
                                    Text(
                                        text = originalTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Text(
                                    text = "Author: $author",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = if (isLocalBook) "Library Book • $totalChapters ch" else "Translated: $translatedChaptersCount / $totalChapters ch",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Description
                        if (displayDesc.isNotBlank()) {
                            HorizontalDivider()
                            Text(
                                text = "Description",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val isLongDesc = displayDesc.length > 180 || displayDesc.count { it == '\n' } > 3
                            Text(
                                text = displayDesc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
                                overflow = if (isDescriptionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
                            )
                            if (isLongDesc) {
                                TextButton(
                                    onClick = { isDescriptionExpanded = !isDescriptionExpanded },
                                    modifier = Modifier.align(Alignment.End),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = if (isDescriptionExpanded) "See less" else "See more",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Actions & Search Bar
            item(key = "search_and_filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search chapters...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chapter_search_input")
                    )

                    // Filter row: All Chapters vs Bookmarked
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !showOnlyBookmarks,
                            onClick = { showOnlyBookmarks = false },
                            label = { Text("All (${chapters.size})") },
                            leadingIcon = if (!showOnlyBookmarks) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = showOnlyBookmarks,
                            onClick = { showOnlyBookmarks = true },
                            label = { Text("Bookmarked (${bookmarkedChapterIds.size})") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showOnlyBookmarks) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showOnlyBookmarks) "Bookmarked Chapters (${filteredChapters.size})" else "Chapters (${filteredChapters.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (!isSelectionMode) {
                            TextButton(
                                onClick = {
                                    isSelectionMode = true
                                    showRangeDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Range Export")
                            }
                        }
                    }
                }
            }

            if (searchQuery.isNotBlank() || showOnlyBookmarks) {
                if (filteredChapters.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (showOnlyBookmarks) "No chapters bookmarked yet. Tap the bookmark icon on any chapter to add it." else "No chapters found matching '$searchQuery'.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Flat filtered list when search or bookmark filter is active
                    itemsIndexed(
                        items = filteredChapters,
                        key = { _, chapter -> chapter.id }
                    ) { index, chapter ->
                        val trTitle = chapterTitlesMap[chapter.id]?.takeIf { it.isNotBlank() }
                        val displayChapterTitle = trTitle ?: chapter.title
                        val isSelected = selectedChapterIds.contains(chapter.id)
                        val isLastRead = chapter.id == lastReadChapterId
                        val prog = chapterProgressMap[chapter.id]
                        val isFullyTranslated = isLocalBook || (prog != null && prog.second > 0 && prog.first >= prog.second)

                        ChapterListItem(
                            chapterNumber = chapter.chapterOrder + 1,
                            title = displayChapterTitle,
                            originalTitle = null,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            isLastRead = isLastRead,
                            isTranslated = isFullyTranslated,
                            isBookmarked = bookmarkedChapterIds.contains(chapter.id),
                            progress = if (isLocalBook) null else prog,
                            onToggleSelect = {
                                selectedChapterIds = if (isSelected) selectedChapterIds - chapter.id else selectedChapterIds + chapter.id
                            },
                            onToggleBookmark = {
                                viewModel.toggleBookmark(bookId, chapter.id)
                            },
                            onClick = {
                                if (isSelectionMode) {
                                    selectedChapterIds = if (isSelected) selectedChapterIds - chapter.id else selectedChapterIds + chapter.id
                                } else {
                                    viewModel.setLastReadChapterId(bookId, chapter.id)
                                    onOpenReader(chapter.id)
                                }
                            }
                        )
                    }
                }
            } else {
                // Grouped into expandable ranges of 100
                chapterGroups.forEach { group ->
                    val isExpanded = expandedGroupIndices.contains(group.groupIndex)
                    val translatedInGroup = groupProgressCounts[group.groupIndex] ?: 0
                    val selectedInGroup = if (selectedChapterIds.isEmpty()) 0 else group.chapters.count { selectedChapterIds.contains(it.id) }

                    item(key = "group_header_${group.groupIndex}") {
                        ChapterGroupHeader(
                            group = group,
                            isExpanded = isExpanded,
                            translatedCount = translatedInGroup,
                            isSelectionMode = isSelectionMode,
                            selectedInGroupCount = selectedInGroup,
                            onToggleExpand = {
                                expandedGroupIndices = if (isExpanded) {
                                    expandedGroupIndices - group.groupIndex
                                } else {
                                    expandedGroupIndices + group.groupIndex
                                }
                            },
                            onToggleSelectGroup = {
                                val groupIds = group.chapters.map { it.id }.toSet()
                                val allSelected = selectedInGroup == group.chapters.size && group.chapters.isNotEmpty()
                                selectedChapterIds = if (allSelected) {
                                    selectedChapterIds - groupIds
                                } else {
                                    selectedChapterIds + groupIds
                                }
                            }
                        )
                    }

                    if (isExpanded) {
                        items(
                            items = group.chapters,
                            key = { it.id }
                        ) { chapter ->
                            val trTitle = chapterTitlesMap[chapter.id]?.takeIf { it.isNotBlank() }
                            val displayChapterTitle = trTitle ?: chapter.title
                            val isSelected = selectedChapterIds.contains(chapter.id)
                            val isLastRead = chapter.id == lastReadChapterId
                            val prog = chapterProgressMap[chapter.id]
                            val isFullyTranslated = isLocalBook || (prog != null && prog.second > 0 && prog.first >= prog.second)

                            ChapterListItem(
                                chapterNumber = chapter.chapterOrder + 1,
                                title = displayChapterTitle,
                                originalTitle = null,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                isLastRead = isLastRead,
                                isTranslated = isFullyTranslated,
                                isBookmarked = bookmarkedChapterIds.contains(chapter.id),
                                progress = if (isLocalBook) null else prog,
                                onToggleSelect = {
                                    selectedChapterIds = if (isSelected) selectedChapterIds - chapter.id else selectedChapterIds + chapter.id
                                },
                                onToggleBookmark = {
                                    viewModel.toggleBookmark(bookId, chapter.id)
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedChapterIds = if (isSelected) selectedChapterIds - chapter.id else selectedChapterIds + chapter.id
                                    } else {
                                        viewModel.setLastReadChapterId(bookId, chapter.id)
                                        onOpenReader(chapter.id)
                                    }
                                },
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Range Selection Dialog
    if (showRangeDialog) {
        RangeExportDialog(
            totalChapters = chapters.size,
            onDismiss = { showRangeDialog = false },
            onConfirm = { fromIndex, toIndex ->
                showRangeDialog = false
                isSelectionMode = true
                val sorted = chapters.sortedBy { it.chapterOrder }
                val start = (fromIndex - 1).coerceIn(0, sorted.size - 1)
                val end = (toIndex - 1).coerceIn(0, sorted.size - 1)
                selectedChapterIds = if (start <= end) {
                    sorted.subList(start, end + 1).map { it.id }.toSet()
                } else {
                    emptySet()
                }
            }
        )
    }
}

@Composable
fun ChapterListItem(
    chapterNumber: Int,
    title: String,
    originalTitle: String?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isLastRead: Boolean,
    isTranslated: Boolean,
    isBookmarked: Boolean = false,
    progress: Pair<Int, Int>?,
    onToggleSelect: () -> Unit,
    onToggleBookmark: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else if (isLastRead) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chapter_item_$chapterNumber")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main click area (opens chapter or toggles select in selection mode)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { if (isSelectionMode) onToggleSelect() else onClick() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Chapter Number Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isLastRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$chapterNumber",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isLastRead) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Title & Status
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isLastRead) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (isLastRead) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "Last Read",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (originalTitle != null) {
                        Text(
                            text = originalTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Translation Status Badge
                if (isTranslated) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Translated",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else if (progress != null && progress.first > 0) {
                    Text(
                        text = "${progress.first}/${progress.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Bookmark Toggle (separate, non-overlapping touch target)
            IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 4.dp)
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Bookmarked" else "Bookmark",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun RangeExportDialog(
    totalChapters: Int,
    onDismiss: () -> Unit,
    onConfirm: (fromIndex: Int, toIndex: Int) -> Unit
) {
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf(totalChapters.toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Chapter Range") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter chapter range between 1 and $totalChapters.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = {
                            fromText = it
                            errorMessage = null
                        },
                        label = { Text("From") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = toText,
                        onValueChange = {
                            toText = it
                            errorMessage = null
                        },
                        label = { Text("To") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val from = fromText.toIntOrNull()
                    val to = toText.toIntOrNull()
                    if (from == null || to == null) {
                        errorMessage = "Please enter valid integers"
                    } else if (from < 1 || to > totalChapters || from > to) {
                        errorMessage = "Invalid range: must be between 1 and $totalChapters and From <= To"
                    } else {
                        onConfirm(from, to)
                    }
                }
            ) {
                Text("Select Range")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

data class ChapterGroup(
    val groupIndex: Int,
    val startChapterNum: Int,
    val endChapterNum: Int,
    val chapters: List<ChapterEntity>
) {
    val title: String
        get() = if (startChapterNum == endChapterNum) "Chapter $startChapterNum" else "Chapter $startChapterNum-$endChapterNum"
}

@Composable
fun ChapterGroupHeader(
    group: ChapterGroup,
    isExpanded: Boolean,
    translatedCount: Int,
    isSelectionMode: Boolean,
    selectedInGroupCount: Int,
    onToggleExpand: () -> Unit,
    onToggleSelectGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onToggleExpand,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isExpanded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chapter_group_${group.groupIndex}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = selectedInGroupCount == group.chapters.size && group.chapters.isNotEmpty(),
                    onCheckedChange = { onToggleSelectGroup() },
                    modifier = Modifier.size(24.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${group.chapters.size} chapters • $translatedCount translated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

