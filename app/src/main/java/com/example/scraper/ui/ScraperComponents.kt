package com.example.scraper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scraper.model.LogLevel
import com.example.scraper.model.ScrapeLogEntry
import com.example.scraper.model.ScrapeStatus
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusChip(status: ScrapeStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ScrapeStatus.COMPLETED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ScrapeStatus.ERROR, ScrapeStatus.NEEDS_LOGIN -> scheme.errorContainer to scheme.onErrorContainer
        ScrapeStatus.PAUSED, ScrapeStatus.STOPPED, ScrapeStatus.IDLE -> scheme.surfaceVariant to scheme.onSurfaceVariant
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (status.isActive) scheme.primary else content.copy(alpha = 0.6f))
        )
        Spacer(Modifier.width(6.dp))
        Text(status.label, color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun LiveLog(logs: List<ScrapeLogEntry>, modifier: Modifier = Modifier, maxEntries: Int = 60) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E2E))
            .padding(10.dp)
    ) {
        if (logs.isEmpty()) {
            Text("No activity yet", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                items(logs.take(maxEntries), key = { it.id }) { entry ->
                    val color = when (entry.level) {
                        LogLevel.SUCCESS -> Color(0xFFA6E3A1)
                        LogLevel.WARNING -> Color(0xFFF9E2AF)
                        LogLevel.ERROR -> Color(0xFFF38BA8)
                        LogLevel.DEBUG -> Color(0xFF94E2D5)
                        LogLevel.INFO -> Color(0xFFCDD6F4)
                    }
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(timeFormat.format(Date(entry.timestamp)), color = Color(0xFF7F849C), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                        Text(entry.message, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun EditProjectDialog(
    initialTitle: String,
    initialAuthor: String?,
    onSave: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, author.ifBlank { null }) }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun formatWhen(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
    }
}

fun formatSeconds(ms: Long): String {
    val tenths = (ms + 50) / 100
    return if (tenths % 10 == 0L) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
}

fun formatCount(value: Long): String = "%,d".format(value)
