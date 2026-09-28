package com.example.scraper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scraper.ScraperGraph
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapedChapterSummary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScrapeProjectScreen(
    viewModel: ScraperViewModel,
    projectId: String,
    onOpenLibraryBook: ((String) -> Unit)?
) {
    val project by remember(projectId) { viewModel.observeProject(projectId) }.collectAsState(initial = null)
    val chapters by remember(projectId) { viewModel.observeChapters(projectId) }.collectAsState(initial = emptyList())
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val busy by viewModel.busyProjects.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chapterToDelete by remember { mutableStateOf<ScrapedChapterSummary?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        if (uri != null) viewModel.exportEpub(projectId, uri)
    }

    val current = project
    if (editing && current != null) {
        EditProjectDialog(current.title, current.author, onSave = { t, a -> editing = false; viewModel.renameProject(projectId, t, a) }, onDismiss = { editing = false })
    }
    if (confirmDelete && current != null) {
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${current.title}\" and its ${current.chapterCount} saved chapters will be deleted from the scraper." +
                if (current.linkedBookId != null) " The copy in your library is kept." else "",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; viewModel.deleteProject(projectId) },
            onDismiss = { confirmDelete = false }
        )
    }
    chapterToDelete?.let { chapter ->
        ConfirmDialog(
            title = "Remove chapter?",
            message = "\"${chapter.title}\" will be removed from this project. Resume won't scrape it again unless you open it yourself.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { chapterToDelete = null; viewModel.deleteChapter(projectId, chapter.id) },
            onDismiss = { chapterToDelete = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.title ?: "Project", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Edit title and author") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuOpen = false; editing = true })
                            DropdownMenuItem(text = { Text("Delete project") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuOpen = false; confirmDelete = true })
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val isBusy = projectId in busy
        val isRunningHere = progress.status.isActive && progress.projectId == projectId
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                ProjectHeader(current, isRunningHere, viewModel)
            }
            item(key = "actions") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRunningHere) {
                            Button(onClick = { viewModel.openBrowser() }, modifier = Modifier.fillMaxWidth()) { Text("Scraping now: open browser") }
                        } else {
                            Button(onClick = { viewModel.resumeProject(projectId) }, modifier = Modifier.fillMaxWidth().testTag("project_resume_button")) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (current.chapterCount == 0) "Start scraping" else "Resume scraping")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { viewModel.openProjectInBrowser(current) }) {
                                Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Open in browser")
                            }
                            OutlinedButton(
                                onClick = { exportLauncher.launch(ScraperGraph.safeFileName(current.title) + ".epub") },
                                enabled = chapters.isNotEmpty() && !isBusy,
                                modifier = Modifier.testTag("project_export_button")
                            ) {
                                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Export EPUB")
                            }
                            OutlinedButton(onClick = { viewModel.shareEpub(projectId) }, enabled = chapters.isNotEmpty() && !isBusy) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share")
                            }
                        }
                        if (viewModel.hasLibrary) {
                            val newChapters = (current.chapterCount - current.linkedChapterCount).coerceAtLeast(0)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(
                                    onClick = { viewModel.saveToLibrary(projectId) },
                                    enabled = chapters.isNotEmpty() && !isBusy,
                                    modifier = Modifier.testTag("project_library_button")
                                ) {
                                    Icon(Icons.Default.LibraryAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                    Text(
                                        when {
                                            current.linkedBookId == null -> "Save to library"
                                            newChapters > 0 -> "Update library copy (+$newChapters)"
                                            else -> "Update library copy"
                                        }
                                    )
                                }
                                if (current.linkedBookId != null && onOpenLibraryBook != null) {
                                    OutlinedButton(onClick = { onOpenLibraryBook(current.linkedBookId) }) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Read")
                                    }
                                }
                                if (viewModel.canTranslate) {
                                    OutlinedButton(onClick = { viewModel.queueTranslation(projectId) }, enabled = chapters.isNotEmpty() && !isBusy) {
                                        Icon(Icons.Default.Translate, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Translate")
                                    }
                                }
                            }
                        }
                        if (isBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                SmallLabel("Building EPUB…")
                            }
                        }
                    }
                }
            }
            item(key = "chapters_header") {
                Text("Chapters (${chapters.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            if (chapters.isEmpty()) {
                item(key = "no_chapters") {
                    Text("No chapters yet. Tap Start scraping.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(chapters, key = { _, c -> c.id }) { index, chapter ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.openPreview(chapter) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            SmallLabel("${formatCount(chapter.characterCount.toLong())} chars · ${chapter.paragraphCount} paragraphs")
                        }
                        IconButton(onClick = { chapterToDelete = chapter }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${chapter.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectHeader(project: ScrapeProject, isRunningHere: Boolean, viewModel: ScraperViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(project.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(if (isRunningHere || !project.status.isActive) project.status else com.example.scraper.model.ScrapeStatus.PAUSED)
            }
            project.author?.let { SmallLabel("by $it") }
            Text(
                "${project.chapterCount} chapters · ${formatCount(project.totalCharacters)} characters · ${project.siteHost}",
                style = MaterialTheme.typography.bodyMedium
            )
            LinkLine("Started at", project.sourceUrl) { viewModel.openProjectInBrowser(project, project.sourceUrl) }
            if (project.continueUrl != project.sourceUrl) {
                LinkLine("Continues at", project.continueUrl) { viewModel.openProjectInBrowser(project, project.continueUrl) }
            }
            project.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            SmallLabel("Updated ${formatWhen(project.updatedAt)}")
        }
    }
}

@Composable
private fun LinkLine(label: String, url: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
        SmallLabel(label)
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
