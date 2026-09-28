package com.example.scraper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scraper.model.ScrapeProject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperHomeScreen(viewModel: ScraperViewModel) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val resumeCandidate by viewModel.resumeCandidate.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scraper", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.open(ScraperScreen.History) }, modifier = Modifier.testTag("scraper_history_button")) {
                        Icon(Icons.Default.History, contentDescription = "Browsing history")
                    }
                    IconButton(onClick = { viewModel.open(ScraperScreen.Settings) }, modifier = Modifier.testTag("scraper_settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Scraper settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (progress.status.isActive) {
                item(key = "active") {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.openBrowser() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(progress.status)
                                Spacer(Modifier.width(8.dp))
                                Text(progress.projectTitle.ifBlank { "Scraping" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.padding(top = 6.dp))
                            Text("${progress.chaptersThisRun} new this run · ${progress.totalChapters} total", style = MaterialTheme.typography.bodyMedium)
                            SmallLabel(progress.message)
                            TextButton(onClick = { viewModel.openBrowser() }) { Text("Open browser") }
                        }
                    }
                }
            }

            resumeCandidate?.let { project ->
                item(key = "resume") {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("scraper_resume_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Continue \"${project.title}\"?", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${project.chapterCount} chapters saved · ${project.status.label.lowercase()}" +
                                    (project.lastError?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.resumeProject(project.id) }, modifier = Modifier.testTag("scraper_resume_button")) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Resume")
                                }
                                TextButton(onClick = { viewModel.dismissResumePrompt(project.id) }) { Text("Not now") }
                            }
                        }
                    }
                }
            }

            item(key = "address") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Open a novel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Open the first chapter you want in the browser, then tap Start. Chapters are saved one by one and the next chapter opens automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Sites like TomatoMTL only show chapters when you're logged in. Log in once inside this browser; it stays logged in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.fillMaxWidth().testTag("scraper_address_input"),
                            singleLine = true,
                            label = { Text("Website or chapter URL") },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { viewModel.openUrl(address) })
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.openUrl(address) }, enabled = address.isNotBlank(), modifier = Modifier.testTag("scraper_go_button")) {
                                Text("Go")
                            }
                            FilledTonalButton(onClick = { viewModel.openBrowser() }, modifier = Modifier.testTag("scraper_open_browser_button")) {
                                Text(if (browserState.url.startsWith("http")) "Back to browser" else "Open browser")
                            }
                        }
                        if (browserState.url.startsWith("http")) {
                            SmallLabel("Last page: ${browserState.title.ifBlank { browserState.url }}")
                        }
                    }
                }
            }

            item(key = "projects_header") {
                Text("Projects (${projects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (projects.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No projects yet. A project is created the first time you tap Start on a chapter page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project, isRunning = progress.status.isActive && progress.projectId == project.id) {
                        viewModel.open(ScraperScreen.Project(project.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ScrapeProject, isRunning: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("scraper_project_${project.id}")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SmallLabel("${project.chapterCount} chapters · ${project.siteHost} · ${formatWhen(project.updatedAt)}")
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(if (isRunning) project.status else if (project.status.isActive) com.example.scraper.model.ScrapeStatus.PAUSED else project.status)
        }
    }
}
