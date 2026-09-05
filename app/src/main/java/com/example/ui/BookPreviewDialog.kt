package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
fun BookPreviewDialog(
    previewState: BookPreviewState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onAddToLibrary: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("book_preview_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Novel Preview",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Cover Image or Placeholder
                if (previewState.tempCoverFile != null && previewState.tempCoverFile.exists()) {
                    AsyncImage(
                        model = previewState.tempCoverFile,
                        contentDescription = "Cover Image",
                        modifier = Modifier
                            .height(180.dp)
                            .width(130.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .height(160.dp)
                            .width(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = previewState.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("preview_book_title")
                )

                // Author
                Text(
                    text = "Author: ${previewState.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Info Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("${previewState.chapterCount} Chapters") }
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Spine Verified") }
                    )
                }

                // Description
                if (previewState.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = previewState.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("preview_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (onAddToLibrary != null) {
                        if (previewState.isLibraryIntent) {
                            OutlinedButton(
                                onClick = onConfirm,
                                modifier = Modifier.testTag("preview_queue_button")
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Translate")
                            }
                            Button(
                                onClick = onAddToLibrary,
                                modifier = Modifier.testTag("preview_library_button")
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add to Library")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onAddToLibrary,
                                modifier = Modifier.testTag("preview_library_button")
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add to Library")
                            }
                            Button(
                                onClick = onConfirm,
                                modifier = Modifier.testTag("preview_queue_button")
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Translate")
                            }
                        }
                    } else {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.testTag("preview_queue_button")
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add to Queue")
                        }
                    }
                }
            }
        }
    }
}
