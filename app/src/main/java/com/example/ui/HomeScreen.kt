package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    booksWithJobs: List<BookWithJob>,
    activeWorkers: Int,
    activeWorkersByJob: Map<String, Int> = emptyMap(),
    exportingBookIds: Set<String> = emptySet(),
    isProcessing: Boolean,
    onSelectSingleEpub: (Uri) -> Unit,
    onSelectMultipleEpubs: (List<Uri>) -> Unit,
    onNavigateToQueue: () -> Unit,
    onOpenNovelDetail: (String) -> Unit = {},
    onExportEpub: (bookId: String, bookTitle: String, exportedFilePath: String?) -> Unit,
    onPauseJob: (String) -> Unit,
    onResumeJob: (String) -> Unit,
    onRetryJob: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    // Single EPUB Picker (triggers preview)
    val singlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSelectSingleEpub(it) }
    }

    // Multiple EPUBs Picker
    val multiplePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onSelectMultipleEpubs(uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "EPUB Translator",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (isProcessing) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable { onNavigateToQueue() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$activeWorkers workers",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Action Header Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_action_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { singlePickerLauncher.launch("application/epub+zip") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("select_single_epub_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Select EPUB")
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = { multiplePickerLauncher.launch("application/epub+zip") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("select_multiple_epubs_button")
                            ) {
                                Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Multiple")
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Novels & Jobs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (booksWithJobs.isNotEmpty()) {
                        Text(
                            text = "${booksWithJobs.size} books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (booksWithJobs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No EPUB novels added yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Select EPUB' above to get started",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                items(booksWithJobs, key = { it.book.id }) { item ->
                    val jobWorkers = item.job?.let { activeWorkersByJob[it.id] } ?: 0
                    val isExporting = exportingBookIds.contains(item.book.id)
                    BookJobCard(
                        item = item,
                        jobActiveWorkers = jobWorkers,
                        isExporting = isExporting,
                        onCardClick = { onOpenNovelDetail(item.book.id) },
                        onExport = onExportEpub,
                        onPause = onPauseJob,
                        onResume = onResumeJob,
                        onRetry = onRetryJob,
                        onDelete = onDeleteBook,
                        onNavigateToQueue = onNavigateToQueue
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun BookJobCard(
    item: BookWithJob,
    jobActiveWorkers: Int = 0,
    isExporting: Boolean = false,
    onCardClick: () -> Unit = {},
    onExport: (bookId: String, bookTitle: String, exportedFilePath: String?) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNavigateToQueue: () -> Unit
) {
    val book = item.book
    val job = item.job

    Card(
        onClick = onCardClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book_item_${book.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                            text = "${book.chapterCount} ch • ${book.totalChunks} chunks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        StatusBadge(status = job?.status ?: "QUEUED")
                    }
                }
            }

            // Progress bar
            if (job != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${job.completedChunks} / ${job.totalChunks}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (jobActiveWorkers > 0) {
                            Text(
                                text = "$jobActiveWorkers active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = "${(job.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (job.failedChunks > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Translation incomplete: ${job.failedChunks} chunks failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (job.errorMessage != null && job.failedChunks == 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = job.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onDelete(book.id) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                when (job?.status) {
                    "COMPLETED" -> {
                        Button(
                            onClick = { onExport(book.id, item.displayTitle, job.exportedUri) },
                            enabled = !isExporting,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Exporting...")
                            } else {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export English EPUB")
                            }
                        }
                    }
                    "RUNNING", "TRANSLATING" -> {
                        OutlinedButton(onClick = { onPause(job.id) }) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(onClick = onNavigateToQueue) {
                            Text("Queue")
                        }
                    }
                    "PAUSING" -> {
                        OutlinedButton(onClick = { }, enabled = false) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pausing...")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(onClick = onNavigateToQueue) {
                            Text("Queue")
                        }
                    }
                    "QUEUED" -> {
                        OutlinedButton(onClick = { onPause(job.id) }) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(onClick = onNavigateToQueue) {
                            Text("Queue")
                        }
                    }
                    "PAUSED" -> {
                        Button(onClick = { onResume(job.id) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume")
                        }
                    }
                    "FAILED" -> {
                        Button(
                            onClick = { onRetry(job.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry Failed")
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = onNavigateToQueue) {
                            Text("View Queue")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val (bgColor, textColor, borderColor) = when (status) {
        "COMPLETED" -> if (isLight) {
            Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), Color(0xFFA5D6A7))
        } else {
            Triple(Color(0xFF1E3A24), Color(0xFFA5D6A7), Color(0xFF2E7D32))
        }
        "RUNNING", "TRANSLATING" -> if (isLight) {
            Triple(Color(0xFFFFEBEE), Color(0xFFC62828), Color(0xFFEF9A9A))
        } else {
            Triple(Color(0xFF4A1012), Color(0xFFFF8A80), Color(0xFFEF5350))
        }
        "PAUSING" -> if (isLight) {
            Triple(Color(0xFFFFF8E1), Color(0xFFF57F17), Color(0xFFFFE082))
        } else {
            Triple(Color(0xFF3E361A), Color(0xFFFFE082), Color(0xFFF57F17))
        }
        "QUEUED" -> if (isLight) {
            Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), Color(0xFF90CAF9))
        } else {
            Triple(Color(0xFF1A2E3E), Color(0xFF90CAF9), Color(0xFF1565C0))
        }
        "PAUSED" -> if (isLight) {
            Triple(Color(0xFFFFF3E0), Color(0xFFE65100), Color(0xFFFFCC80))
        } else {
            Triple(Color(0xFF3E2D1A), Color(0xFFFFCC80), Color(0xFFE65100))
        }
        "FAILED" -> if (isLight) {
            Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), Color(0xFFEF5350))
        } else {
            Triple(Color(0xFF3E1F24), Color(0xFFEF9A9A), Color(0xFFC62828))
        }
        "CANCELLED" -> if (isLight) {
            Triple(Color(0xFFF5F5F5), Color(0xFF616161), Color(0xFFE0E0E0))
        } else {
            Triple(Color(0xFF2B2930), Color(0xFF9E9E9E), Color(0xFF616161))
        }
        else -> if (isLight) {
            Triple(Color(0xFFF5F5F5), Color(0xFF424242), Color(0xFFE0E0E0))
        } else {
            Triple(Color(0xFF2B2930), Color(0xFFCAC4D0), Color(0xFF49454F))
        }
    }

    val displayLabel = when (status) {
        "PAUSING" -> "PAUSING..."
        else -> status
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
