package com.example.scraper.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Entry point of the scraper UI. It owns its own small back stack (home, browser, project, history,
 * settings) so it can be embedded as a tab in another app or used as a whole app.
 *
 * @param onOpenLibraryBook opens a library book in the host app (only when the host has a library).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperRoot(
    viewModel: ScraperViewModel,
    modifier: Modifier = Modifier,
    onOpenLibraryBook: ((bookId: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val shareFile by viewModel.shareFile.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = screen != ScraperScreen.Home && screen != ScraperScreen.Browser) {
        viewModel.back()
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(text)
    }

    LaunchedEffect(shareFile) {
        val file = shareFile ?: return@LaunchedEffect
        viewModel.consumeShareFile()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share EPUB"))
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Couldn't share: ${e.message}")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = screen) {
            ScraperScreen.Home -> ScraperHomeScreen(viewModel)
            ScraperScreen.Browser -> ScraperBrowserScreen(viewModel)
            ScraperScreen.History -> ScraperHistoryScreen(viewModel)
            ScraperScreen.Settings -> ScraperSettingsScreen(viewModel)
            is ScraperScreen.Project -> ScrapeProjectScreen(viewModel, current.projectId, onOpenLibraryBook)
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    preview?.let { chapterPreview ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { viewModel.closePreview() }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(chapterPreview.chapter.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SmallLabel("${formatCount(chapterPreview.chapter.characterCount.toLong())} characters · ${chapterPreview.chapter.paragraphCount} paragraphs")
                SmallLabel(chapterPreview.chapter.sourceUrl)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .padding(top = 12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    chapterPreview.paragraphs.forEach { paragraph ->
                        Text(paragraph, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
            }
        }
    }
}
