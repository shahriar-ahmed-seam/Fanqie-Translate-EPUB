package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.db.BookGroupCrossRefEntity
import com.example.data.db.BookType
import com.example.data.db.LibraryGroupEntity
import com.example.data.repository.LibraryViewMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    booksWithJobs: List<BookWithJob>,
    libraryGroups: List<LibraryGroupEntity> = emptyList(),
    bookGroupCrossRefs: List<BookGroupCrossRefEntity> = emptyList(),
    exportingBookIds: Set<String> = emptySet(),
    viewMode: LibraryViewMode = LibraryViewMode.GRID,
    onToggleViewMode: (LibraryViewMode) -> Unit = {},
    onAddToLibrary: (Uri) -> Unit,
    onCreateGroup: (String) -> Unit = {},
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onSetBookGroups: (bookId: String, selectedGroupIds: Set<String>) -> Unit = { _, _ -> },
    onOpenNovelDetail: (String) -> Unit = {},
    onExportEpub: (bookId: String, bookTitle: String, exportedFilePath: String?) -> Unit = { _, _, _ -> },
    onDeleteBook: (String) -> Unit
) {
    // Local EPUB Picker (Library-only import)
    val libraryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onAddToLibrary(it) }
    }

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>("ALL") }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var groupToRename by remember { mutableStateOf<LibraryGroupEntity?>(null) }
    var groupToDelete by remember { mutableStateOf<LibraryGroupEntity?>(null) }
    var bookToManageGroups by remember { mutableStateOf<BookWithJob?>(null) }
    var bookForActions by remember { mutableStateOf<BookWithJob?>(null) }

    val activeGroup = libraryGroups.firstOrNull { it.id == selectedGroupId }
    val filteredBooks = remember(booksWithJobs, selectedGroupId, bookGroupCrossRefs) {
        if (selectedGroupId == null || selectedGroupId == "ALL") {
            booksWithJobs
        } else {
            val matchingBookIds = bookGroupCrossRefs
                .filter { it.groupId == selectedGroupId }
                .map { it.bookId }
                .toSet()
            booksWithJobs.filter {
                matchingBookIds.contains(it.book.id) ||
                (selectedGroupId == "default_translated" && it.book.bookType == BookType.TRANSLATION) ||
                (selectedGroupId == "default_local" && it.book.bookType == BookType.LOCAL)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Library",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    // View mode toggle button (Grid / List)
                    IconButton(
                        onClick = {
                            val nextMode = if (viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID
                            onToggleViewMode(nextMode)
                        },
                        modifier = Modifier.testTag("library_view_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (viewMode == LibraryViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (viewMode == LibraryViewMode.GRID) "Switch to List View" else "Switch to Grid View"
                        )
                    }

                    FilledTonalButton(
                        onClick = { libraryPickerLauncher.launch("application/epub+zip") },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("library_add_epub_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add EPUB", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { padding ->
        if (viewMode == LibraryViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 124.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Group Tabs Section (Full row span)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryGroupTabsSection(
                        booksWithJobs = booksWithJobs,
                        filteredBooksCount = filteredBooks.size,
                        libraryGroups = libraryGroups,
                        bookGroupCrossRefs = bookGroupCrossRefs,
                        selectedGroupId = selectedGroupId,
                        activeGroup = activeGroup,
                        onSelectGroup = { selectedGroupId = it },
                        onCreateGroupClick = { showCreateGroupDialog = true },
                        onRenameGroupClick = { groupToRename = it },
                        onDeleteGroupClick = { groupToDelete = it }
                    )
                }

                // Books Grid
                if (filteredBooks.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryEmptyState(
                            selectedGroupId = selectedGroupId,
                            activeGroupName = activeGroup?.name
                        )
                    }
                } else {
                    items(filteredBooks, key = { it.book.id }) { item ->
                        LibraryGridBookCard(
                            item = item,
                            onClick = { onOpenNovelDetail(item.book.id) },
                            onLongClick = { bookForActions = item }
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Group Tabs Section
                item {
                    LibraryGroupTabsSection(
                        booksWithJobs = booksWithJobs,
                        filteredBooksCount = filteredBooks.size,
                        libraryGroups = libraryGroups,
                        bookGroupCrossRefs = bookGroupCrossRefs,
                        selectedGroupId = selectedGroupId,
                        activeGroup = activeGroup,
                        onSelectGroup = { selectedGroupId = it },
                        onCreateGroupClick = { showCreateGroupDialog = true },
                        onRenameGroupClick = { groupToRename = it },
                        onDeleteGroupClick = { groupToDelete = it }
                    )
                }

                // Books List
                if (filteredBooks.isEmpty()) {
                    item {
                        LibraryEmptyState(
                            selectedGroupId = selectedGroupId,
                            activeGroupName = activeGroup?.name
                        )
                    }
                } else {
                    items(filteredBooks, key = { it.book.id }) { item ->
                        val isExporting = exportingBookIds.contains(item.book.id)
                        LibraryBookCard(
                            item = item,
                            isExporting = isExporting,
                            onCardClick = { onOpenNovelDetail(item.book.id) },
                            onExport = onExportEpub,
                            onDelete = onDeleteBook,
                            onManageGroups = { bookToManageGroups = item }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // 1. Create Group Dialog
    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("Create Library Group") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            onCreateGroup(groupName.trim())
                            showCreateGroupDialog = false
                        }
                    },
                    enabled = groupName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. Rename Group Dialog
    if (groupToRename != null) {
        val target = groupToRename!!
        var newName by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { groupToRename = null },
            title = { Text("Rename Group") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRenameGroup(target.id, newName.trim())
                            groupToRename = null
                        }
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Delete Group Dialog
    if (groupToDelete != null) {
        val target = groupToDelete!!
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("Delete Group") },
            text = {
                Text("Delete '${target.name}'? The books in this group will remain in your library.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(target.id)
                        groupToDelete = null
                        if (selectedGroupId == target.id) {
                            selectedGroupId = "ALL"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Manage Book Groups Dialog
    if (bookToManageGroups != null) {
        val item = bookToManageGroups!!
        val bookId = item.book.id
        val initialAssigned = remember(bookId, bookGroupCrossRefs) {
            val assigned = bookGroupCrossRefs.filter { it.bookId == bookId }.map { it.groupId }.toMutableSet()
            if (item.book.bookType == BookType.TRANSLATION) assigned.add("default_translated")
            if (item.book.bookType == BookType.LOCAL) assigned.add("default_local")
            assigned
        }
        var selectedIds by remember { mutableStateOf(initialAssigned.toSet()) }

        AlertDialog(
            onDismissRequest = { bookToManageGroups = null },
            title = {
                Text(
                    text = "Manage Groups",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (libraryGroups.isEmpty()) {
                        Text("No custom groups created yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(libraryGroups, key = { it.id }) { group ->
                                val isChecked = selectedIds.contains(group.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = isChecked,
                                            role = Role.Checkbox,
                                            onValueChange = { checked ->
                                                selectedIds = if (checked) selectedIds + group.id else selectedIds - group.id
                                            }
                                        )
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = null
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSetBookGroups(bookId, selectedIds)
                        bookToManageGroups = null
                    }
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToManageGroups = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 5. Action Bottom Sheet for Grid Items (Long-Press)
    if (bookForActions != null) {
        val target = bookForActions!!
        LibraryActionBottomSheet(
            item = target,
            isExporting = exportingBookIds.contains(target.book.id),
            onDismiss = { bookForActions = null },
            onOpen = {
                val bId = target.book.id
                bookForActions = null
                onOpenNovelDetail(bId)
            },
            onManageGroups = {
                val itemToManage = target
                bookForActions = null
                bookToManageGroups = itemToManage
            },
            onExport = { bId, title, path ->
                bookForActions = null
                onExportEpub(bId, title, path)
            },
            onDelete = { bId ->
                bookForActions = null
                onDeleteBook(bId)
            }
        )
    }
}

@Composable
fun LibraryBookCard(
    item: BookWithJob,
    isExporting: Boolean = false,
    onCardClick: () -> Unit,
    onExport: (bookId: String, bookTitle: String, exportedFilePath: String?) -> Unit,
    onDelete: (String) -> Unit,
    onManageGroups: () -> Unit
) {
    val book = item.book
    val job = item.job
    val isLocalBook = book.isLocalBook || job == null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_book_${book.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Clickable content area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCardClick)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Cover thumbnail
                    if (book.coverPath != null && File(book.coverPath).exists()) {
                        AsyncImage(
                            model = File(book.coverPath),
                            contentDescription = "Cover",
                            modifier = Modifier
                                .width(60.dp)
                                .height(84.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .width(60.dp)
                                .height(84.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${book.chapterCount} chapters",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            StatusBadge(status = if (isLocalBook) "LOCAL" else (job?.status ?: "COMPLETED"))
                        }
                    }
                }
            }

            // Bottom action row (independent touch target area)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onManageGroups,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = "Manage Groups",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { onDelete(book.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (!isLocalBook && job?.status == "COMPLETED") {
                    OutlinedButton(
                        onClick = { onExport(book.id, item.displayTitle, job.exportedUri) },
                        enabled = !isExporting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isExporting) "Exporting..." else "Export", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onCardClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Read")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridBookCard(
    item: BookWithJob,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = item.book
    val job = item.job
    val isLocalBook = book.isLocalBook || job == null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("library_book_${book.id}")
            .testTag("library_grid_book_${book.id}")
    ) {
        // Cover container (2:3 portrait ratio, rounded corners, clean borderless)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (book.coverPath != null && File(book.coverPath).exists()) {
                AsyncImage(
                    model = File(book.coverPath),
                    contentDescription = "Cover of ${item.displayTitle}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Themed placeholder matching Mihon-style clean look
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.displayTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Top-start status badge if local or translating
            if (isLocalBook) {
                Surface(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "LOCAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else if (job.status in listOf("RUNNING", "TRANSLATING", "QUEUED", "PAUSED", "PAUSING", "FAILED")) {
                val badgeColor = when (job.status) {
                    "FAILED" -> MaterialTheme.colorScheme.error
                    "PAUSED", "PAUSING" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = if (job.status in listOf("RUNNING", "TRANSLATING")) "${(job.progress * 100).toInt()}%" else job.status,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Chapter count badge at bottom-end (Mihon style badge)
            if (book.chapterCount > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 10.dp),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = "${book.chapterCount} ch",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // English / Display Title below cover (2 lines max, ellipsis)
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryActionBottomSheet(
    item: BookWithJob,
    isExporting: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onManageGroups: () -> Unit,
    onExport: (bookId: String, bookTitle: String, exportedFilePath: String?) -> Unit,
    onDelete: (String) -> Unit
) {
    val book = item.book
    val job = item.job
    val isLocalBook = book.isLocalBook || job == null

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header: Cover, Title, Author, Chapter count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (book.coverPath != null && File(book.coverPath).exists()) {
                    AsyncImage(
                        model = File(book.coverPath),
                        contentDescription = null,
                        modifier = Modifier
                            .width(48.dp)
                            .height(68.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(68.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${book.author} • ${book.chapterCount} chapters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 1. Open Novel
            ListItem(
                headlineContent = { Text("Open Novel") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
            )

            // 2. Manage Groups
            ListItem(
                headlineContent = { Text("Manage Groups") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManageGroups)
            )

            // 3. Export EPUB (if available)
            if (!isLocalBook && job?.status == "COMPLETED") {
                ListItem(
                    headlineContent = { Text(if (isExporting) "Exporting EPUB..." else "Export EPUB") },
                    leadingContent = {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !isExporting,
                            onClick = { onExport(book.id, item.displayTitle, job.exportedUri) }
                        )
                )
            }

            // 4. Delete Novel
            ListItem(
                headlineContent = {
                    Text("Delete Novel", color = MaterialTheme.colorScheme.error)
                },
                leadingContent = {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onDelete(book.id) })
            )
        }
    }
}

@Composable
fun LibraryGroupTabsSection(
    booksWithJobs: List<BookWithJob>,
    filteredBooksCount: Int,
    libraryGroups: List<LibraryGroupEntity>,
    bookGroupCrossRefs: List<BookGroupCrossRefEntity>,
    selectedGroupId: String?,
    activeGroup: LibraryGroupEntity?,
    onSelectGroup: (String) -> Unit,
    onCreateGroupClick: () -> Unit,
    onRenameGroupClick: (LibraryGroupEntity) -> Unit,
    onDeleteGroupClick: (LibraryGroupEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Groups",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (filteredBooksCount > 0) {
                Text(
                    text = "$filteredBooksCount books",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                FilterChip(
                    selected = selectedGroupId == "ALL",
                    onClick = { onSelectGroup("ALL") },
                    label = { Text("All (${booksWithJobs.size})") },
                    leadingIcon = if (selectedGroupId == "ALL") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            items(libraryGroups, key = { it.id }) { group ->
                val count = remember(booksWithJobs, group.id, bookGroupCrossRefs) {
                    val matchingIds = bookGroupCrossRefs.filter { it.groupId == group.id }.map { it.bookId }.toSet()
                    booksWithJobs.count {
                        matchingIds.contains(it.book.id) ||
                                (group.id == "default_translated" && it.book.bookType == BookType.TRANSLATION) ||
                                (group.id == "default_local" && it.book.bookType == BookType.LOCAL)
                    }
                }
                FilterChip(
                    selected = selectedGroupId == group.id,
                    onClick = { onSelectGroup(group.id) },
                    label = { Text("${group.name} ($count)") },
                    leadingIcon = if (selectedGroupId == group.id) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            item {
                AssistChip(
                    onClick = onCreateGroupClick,
                    label = { Text("New Group") },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = "Add Group", modifier = Modifier.size(16.dp))
                    }
                )
            }
        }

        // Options row if a custom group is selected
        if (activeGroup != null && !activeGroup.isSystemGroup) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onRenameGroupClick(activeGroup) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rename Group", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { onDeleteGroupClick(activeGroup) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete Group", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun LibraryEmptyState(
    selectedGroupId: String?,
    activeGroupName: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (selectedGroupId == "ALL") "Your library is empty" else "No books in '${activeGroupName ?: "this group"}'",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (selectedGroupId == "ALL") "Tap 'Add EPUB' above to import an EPUB to read" else "Manage groups on any book to add it here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
