package com.example.tomato.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.tomato.EngineSetup
import com.example.tomato.TomatoBook
import com.example.tomato.TomatoEngineInstaller
import com.example.tomato.data.TomatoBookEntity
import com.example.tomato.data.TomatoLibraryKind
import com.example.tomato.data.TomatoState
import com.example.tomato.formatCount
import com.example.tomato.statusLabel
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/** The Tomato tab: find Fanqie novels by ID, download them (in Chinese), then translate or shelve them. */
@Composable
fun TomatoRoot(
    viewModel: TomatoViewModel,
    onOpenLibraryBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val detail by viewModel.detail.collectAsStateLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    val book = detail
    if (book != null) {
        BackHandler { viewModel.closeDetail() }
        TomatoDetailScreen(book, viewModel, snackbar, onOpenLibraryBook, modifier)
    } else {
        TomatoHomeScreen(viewModel, snackbar, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TomatoHomeScreen(viewModel: TomatoViewModel, snackbar: SnackbarHostState, modifier: Modifier) {
    val setup by viewModel.engineSetup.collectAsStateLifecycle()
    val query by viewModel.query.collectAsStateLifecycle()
    val lookup by viewModel.lookup.collectAsStateLifecycle()
    val books by viewModel.books.collectAsStateLifecycle()
    val busy by viewModel.busy.collectAsStateLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val engineReady = setup is EngineSetup.Ready

    val later = books.filter { it.state == TomatoState.LATER }
    val downloads = books.filter { it.state != TomatoState.LATER }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🍅", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Tomato Novels", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.testTag("tomato_settings")) {
                        Icon(Icons.Default.Settings, contentDescription = "Tomato engine settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!engineReady) {
                item(key = "engine") { EngineSetupCard(setup, onInstall = viewModel::installEngine) }
            }
            item(key = "search") {
                SearchBox(
                    query = query,
                    enabled = engineReady,
                    loading = lookup == LookupUi.Loading,
                    onQueryChange = viewModel::setQuery,
                    onSearch = viewModel::search
                )
            }
            when (val state = lookup) {
                is LookupUi.Results -> {
                    item(key = "results_header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.byId) "Novel found" else "${state.books.size} results",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::clearResults) { Text("Clear") }
                        }
                    }
                    items(state.books, key = { "result_${it.bookId}" }) { book ->
                        val saved = books.firstOrNull { it.bookId == book.bookId }
                        ResultCard(book, saved, viewModel.coverFile(book.bookId)) { viewModel.openDetail(book) }
                    }
                }
                is LookupUi.Message -> item(key = "lookup_message") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("tomato_lookup_message")
                    ) {
                        Text(state.text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Unit
            }
            item(key = "tabs") {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Downloads (${downloads.size})") }, modifier = Modifier.testTag("tomato_tab_downloads"))
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Download later (${later.size})") }, modifier = Modifier.testTag("tomato_tab_later"))
                }
            }
            val list = if (tab == 0) downloads else later
            if (list.isEmpty()) {
                item(key = "empty_$tab") {
                    Text(
                        if (tab == 0) "Novels you download appear here, in Chinese. From here you can translate them or add them to your library."
                        else "Novels you save for later appear here. Download them whenever you like.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp)
                    )
                }
            } else {
                items(list, key = { "book_${it.bookId}" }) { entity ->
                    SavedBookRow(
                        entity = entity,
                        coverFile = viewModel.coverFile(entity.bookId),
                        busy = entity.bookId in busy,
                        engineReady = engineReady,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    if (showSettings) EngineSettingsDialog(setup, viewModel) { showSettings = false }
}

@Composable
private fun SearchBox(
    query: String,
    enabled: Boolean,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            singleLine = true,
            label = { Text("Novel ID, link or title") },
            placeholder = { Text("e.g. 7143038691944959011") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSearch, enabled = enabled && query.isNotBlank(), modifier = Modifier.testTag("tomato_search_button")) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("tomato_search_field")
        )
        Text(
            "Paste the ID from a Fanqie book page (fanqienovel.com/page/…) or the whole link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EngineSetupCard(setup: EngineSetup, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("tomato_engine_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Set up the Tomato engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Downloads use the engine of the open-source Fanqie Novel Downloader project. " +
                    "It is downloaded once from GitHub (about 15 to 21 MB) and checked before use.",
                style = MaterialTheme.typography.bodyMedium
            )
            when (setup) {
                is EngineSetup.NotInstalled -> if (setup.unsupportedAbi) {
                    Text("The engine isn't available for this phone's processor.", color = MaterialTheme.colorScheme.error)
                } else {
                    Button(onClick = onInstall, modifier = Modifier.testTag("tomato_install_engine")) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download engine")
                    }
                }
                is EngineSetup.Installing -> {
                    val progress = setup.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (progress != null) "${setup.message} ${(progress * 100).toInt()}%" else setup.message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is EngineSetup.Failed -> {
                    Text(setup.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onInstall) { Text("Try again") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ResultCard(book: TomatoBook, saved: TomatoBookEntity?, coverFile: File, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("tomato_result_${book.bookId}")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TomatoCover(book.title, book.coverUrl, coverFile, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(bookStats(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                if (saved != null) Text(stateLabel(saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                if (book.abstractText.isNotBlank()) {
                    Text(book.abstractText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SavedBookRow(
    entity: TomatoBookEntity,
    coverFile: File,
    busy: Boolean,
    engineReady: Boolean,
    viewModel: TomatoViewModel
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exporter = rememberEpubExporter(entity, viewModel)
    Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.openDetail(entity) }.testTag("tomato_book_${entity.bookId}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TomatoCover(entity.title, entity.coverUrl, coverFile, 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entity.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        stateLabel(entity),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entity.state == TomatoState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 2
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions for ${entity.title}")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            BookMenuItems(entity, engineReady, viewModel, exporter, onDelete = { confirmDelete = true }) { menu = false }
                        }
                    }
                }
            }
            if (entity.state in TomatoState.ACTIVE) DownloadProgress(entity)
            if (entity.state in TomatoState.HAS_FILE && entity.libraryKind == TomatoLibraryKind.NONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.translate(entity.bookId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Translate", maxLines = 1)
                    }
                    OutlinedButton(onClick = { viewModel.addToBookshelf(entity.bookId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Add to shelf", maxLines = 1)
                    }
                }
            }
        }
    }
    if (confirmDelete) DeleteDialog(entity, onConfirm = { viewModel.delete(entity.bookId) }) { confirmDelete = false }
}

@Composable
private fun BookMenuItems(
    entity: TomatoBookEntity,
    engineReady: Boolean,
    viewModel: TomatoViewModel,
    exporter: () -> Unit,
    onDelete: () -> Unit,
    close: () -> Unit
) {
    val hasFile = entity.state in TomatoState.HAS_FILE
    when {
        entity.state in TomatoState.ACTIVE -> DropdownMenuItem(text = { Text("Cancel download") }, onClick = { close(); viewModel.cancel(entity.bookId) })
        entity.state == TomatoState.LATER -> {
            DropdownMenuItem(text = { Text("Download now") }, enabled = engineReady, onClick = { close(); viewModel.downloadNow(entity.toBook()) })
            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { close(); viewModel.removeFromLater(entity.bookId) })
        }
        else -> {
            if (hasFile) {
                DropdownMenuItem(text = { Text("Translate") }, onClick = { close(); viewModel.translate(entity.bookId) })
                DropdownMenuItem(text = { Text("Add to bookshelf") }, onClick = { close(); viewModel.addToBookshelf(entity.bookId) })
                DropdownMenuItem(text = { Text("Export EPUB") }, onClick = { close(); exporter() })
            }
            DropdownMenuItem(
                text = { Text(if (hasFile) "Redownload" else "Try again") },
                enabled = engineReady,
                onClick = { close(); viewModel.redownload(entity.bookId) }
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TomatoDetailScreen(
    book: TomatoBook,
    viewModel: TomatoViewModel,
    snackbar: SnackbarHostState,
    onOpenLibraryBook: (String) -> Unit,
    modifier: Modifier
) {
    val entity by viewModel.detailEntity.collectAsStateLifecycle()
    val busySet by viewModel.busy.collectAsStateLifecycle()
    val setup by viewModel.engineSetup.collectAsStateLifecycle()
    val busy = book.bookId in busySet
    val engineReady = setup is EngineSetup.Ready
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(book.bookId) { mutableStateOf(false) }
    val current = entity?.takeIf { it.bookId == book.bookId }
    val exporter = rememberEpubExporter(current, viewModel)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Novel", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDetail, modifier = Modifier.testTag("tomato_detail_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TomatoCover(book.title, book.coverUrl, viewModel.coverFile(book.bookId), 112.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer {
                        Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("tomato_detail_title"))
                    }
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        book.statusLabel().takeIf { it.isNotBlank() }?.let { InfoChip(it) }
                        book.category.split('/', '、', ',').map { it.trim() }.filter { it.isNotBlank() }.take(3).forEach { InfoChip(it) }
                    }
                    Text(bookStats(book), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            ActionPanel(book, current, busy, engineReady, viewModel, exporter, onOpenLibraryBook) { confirmDelete = true }

            if (book.abstractText.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Description", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(
                            book.abstractText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (book.abstractText.length > 160) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
                    }
                }
            }
            HorizontalDivider()
            SelectionContainer {
                Text("Novel ID: ${book.bookId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Downloads are the original Chinese text. Translate them or add them to your library from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete && current != null) DeleteDialog(current, onConfirm = { viewModel.delete(current.bookId) }) { confirmDelete = false }
}

@Composable
private fun ActionPanel(
    book: TomatoBook,
    entity: TomatoBookEntity?,
    busy: Boolean,
    engineReady: Boolean,
    viewModel: TomatoViewModel,
    exporter: () -> Unit,
    onOpenLibraryBook: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val state = entity?.state
            when {
                state != null && state in TomatoState.ACTIVE -> {
                    DownloadProgress(entity)
                    OutlinedButton(onClick = { viewModel.cancel(book.bookId) }, enabled = !busy, modifier = Modifier.testTag("tomato_cancel")) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel download")
                    }
                }
                state != null && state in TomatoState.HAS_FILE -> {
                    Text(stateLabel(entity), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    entity.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (entity.libraryKind != TomatoLibraryKind.NONE && entity.libraryBookId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(
                                onClick = { onOpenLibraryBook(entity.libraryBookId) },
                                label = { Text(if (entity.libraryKind == TomatoLibraryKind.TRANSLATION) "In library · translating" else "In library") }
                            )
                            TextButton(onClick = { onOpenLibraryBook(entity.libraryBookId) }) { Text("Open") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.translate(book.bookId) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("tomato_translate")) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Translate", maxLines = 1)
                        }
                        OutlinedButton(onClick = { viewModel.addToBookshelf(book.bookId) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("tomato_add_shelf")) {
                            Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Bookshelf", maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = exporter, enabled = !busy) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export")
                        }
                        TextButton(onClick = { viewModel.redownload(book.bookId) }, enabled = !busy && engineReady, modifier = Modifier.testTag("tomato_redownload")) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Redownload")
                        }
                        TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag("tomato_delete")) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                else -> {
                    if (state == TomatoState.FAILED || state == TomatoState.CANCELED) {
                        Text(entity.message ?: "The download didn't finish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = { viewModel.downloadNow(book) },
                        enabled = !busy && engineReady,
                        modifier = Modifier.fillMaxWidth().testTag("tomato_download_now")
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (state == TomatoState.FAILED || state == TomatoState.CANCELED) "Try again" else "Download now")
                    }
                    when (state) {
                        null -> OutlinedButton(onClick = { viewModel.saveForLater(book) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("tomato_download_later")) {
                            Icon(Icons.Default.WatchLater, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download later")
                        }
                        TomatoState.LATER -> OutlinedButton(onClick = { viewModel.removeFromLater(book.bookId) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("tomato_remove_later")) {
                            Text("Saved for later · Remove")
                        }
                        else -> TextButton(onClick = onDelete, enabled = !busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    if (!engineReady) {
                        Text("Set up the Tomato engine first (Tomato tab).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(entity: TomatoBookEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("tomato_progress_${entity.bookId}")) {
        if (entity.progressTotal > 0) {
            LinearProgressIndicator(
                progress = { (entity.progressDone.toFloat() / entity.progressTotal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(entity.message ?: "Downloading…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun TomatoCover(title: String, url: String, coverFile: File, width: Dp) {
    val local = remember(coverFile.path) { coverFile.takeIf { it.isFile && it.length() > 0 } }
    val model: Any? = local ?: url.ifBlank { null }
    Box(
        modifier = Modifier
            .width(width)
            .height(width * 4 / 3)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .semantics { contentDescription = "Cover of $title" },
        contentAlignment = Alignment.Center
    ) {
        Text("🍅", fontSize = (width.value / 3).sp)
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DeleteDialog(entity: TomatoBookEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this download?") },
        text = {
            Text(
                "\"${entity.title}\" and its downloaded EPUB are removed from the Tomato tab." +
                    if (entity.libraryBookId != null) " The copy in your library stays." else ""
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } }
    )
}

@Composable
private fun EngineSettingsDialog(setup: EngineSetup, viewModel: TomatoViewModel, onDismiss: () -> Unit) {
    val update by viewModel.engineUpdate.collectAsStateLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { viewModel.dismissEngineUpdate(); onDismiss() },
        title = { Text("Tomato engine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val engine = (setup as? EngineSetup.Ready)?.engine
                Text(
                    if (engine != null) "Version ${engine.label} (${engine.abi})" else "Not installed",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "The engine is the downloader core of ${TomatoEngineInstaller.UPSTREAM_URL.removePrefix("https://")}, " +
                        "made by another developer. It runs in its own process: if it crashes, the rest of the app keeps working.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "If downloads stop working (Fanqie changes things now and then), check for a newer engine. " +
                        "A new engine is only used if it starts correctly on this phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                update?.let { state ->
                    if (state.running) {
                        val p = state.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
                if (engine != null) {
                    TextButton(onClick = { confirmRemove = true }, enabled = update?.running != true) {
                        Text("Remove engine", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (setup is EngineSetup.Ready) {
                TextButton(onClick = viewModel::updateEngine, enabled = update?.running != true, modifier = Modifier.testTag("tomato_update_engine")) {
                    Text("Check for update")
                }
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.dismissEngineUpdate(); onDismiss() }) { Text("Close") } }
    )
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the engine?") },
            text = { Text("Downloaded books stay. You can download the engine again any time.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.removeEngine(); onDismiss() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

/** Opens the system "save as" screen for the book's EPUB. */
@Composable
private fun rememberEpubExporter(entity: TomatoBookEntity?, viewModel: TomatoViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        if (uri != null && entity != null) viewModel.export(entity.bookId, uri)
    }
    return {
        if (entity != null) {
            val name = entity.title.ifBlank { entity.bookId }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            launcher.launch("$name.epub")
        }
    }
}

private fun stateLabel(entity: TomatoBookEntity): String = when (entity.state) {
    TomatoState.LATER -> "Saved for later"
    TomatoState.QUEUED -> "Starting download…"
    TomatoState.DOWNLOADING -> entity.message ?: "Downloading…"
    TomatoState.DOWNLOADED, TomatoState.PARTIAL -> buildString {
        append("Downloaded")
        if (entity.epubSizeBytes > 0) append(" · ").append(formatSize(entity.epubSizeBytes))
        if (entity.progressTotal > 0) append(" · ").append(NumberFormat.getInstance(Locale.US).format(entity.progressTotal)).append(" chapters")
        if (entity.state == TomatoState.PARTIAL) append(" · ${entity.missingChapters} missing")
        when (entity.libraryKind) {
            TomatoLibraryKind.TRANSLATION -> append(" · translating")
            TomatoLibraryKind.LOCAL -> append(" · on bookshelf")
        }
    }
    TomatoState.FAILED -> "Failed: ${entity.message ?: "unknown error"}"
    TomatoState.CANCELED -> "Download canceled"
    else -> entity.state
}

private fun bookStats(book: TomatoBook): String = listOfNotNull(
    book.chapterCount.takeIf { it > 0 }?.let { "${NumberFormat.getInstance(Locale.US).format(it)} chapters" },
    book.wordCount.takeIf { it > 0 }?.let { "${formatCount(it)} characters" },
    book.readCount.takeIf { it > 0 }?.let { "${formatCount(it)} readers" }
).joinToString(" · ")

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateLifecycle(): androidx.compose.runtime.State<T> =
    collectAsStateWithLifecycle()
