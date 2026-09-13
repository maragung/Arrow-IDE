package com.maragung.arrowide.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.archive.ArchiveContents
import com.maragung.arrowide.archive.ArchiveReader
import java.io.File

/**
 * View Archive (plan #30): lists an archive's contents straight from the
 * ZIP central directory / TAR headers — no extraction, no decompression of
 * the payload. Tapping a file previews its text content (single-entry
 * extraction, capped). Archives are read from local storage only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    archive: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reader = remember { ArchiveReader() }
    val contents = remember(archive) { runCatching { reader.read(archive) }.getOrNull() }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = archive.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val entries = when (contents) {
            is ArchiveContents.Zip -> contents.entries
            is ArchiveContents.Tar -> contents.entries
            else -> null
        }
        if (entries == null) {
            CenteredText(padding, "This file is not a readable archive (zip or tar).")
        } else if (entries.isEmpty()) {
            CenteredText(padding, "The archive is empty.")
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.name }) { entry ->
                    ArchiveRow(
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        onOpen = {
                            if (!entry.isDirectory) {
                                preview = entry.name to (
                                    runCatching {
                                        reader.readEntry(archive, entry.name)
                                    }.getOrNull() ?: "(Preview not available for this entry.)"
                                    )
                            }
                        },
                    )
                }
            }
        }
    }

    preview?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = {
                Text(
                    text = name,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun CenteredText(padding: PaddingValues, text: String) {
    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchiveRow(
    name: String,
    isDirectory: Boolean,
    size: Long,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDirectory, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isDirectory) {
                Text(
                    text = formatSize(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
}
