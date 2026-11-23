package com.hachicorp.nezh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.FolderStatus
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.playback.QueueSnapshot

@Composable
internal fun LibraryTab(
    library: LibraryState,
    currentMediaId: String?,
    onTrackClick: (Int) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (library.isScanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            !library.loaded -> Unit
            library.folders.isEmpty() -> EmptyMessage(
                text = stringResource(R.string.library_empty_no_folders),
                actionLabel = stringResource(R.string.action_add_folder),
                onAction = onAddFolder,
            )
            library.tracks.isEmpty() -> EmptyMessage(
                text = stringResource(
                    if (library.isScanning) R.string.library_scanning else R.string.library_empty_no_tracks
                ),
            )
            else -> {
                Text(
                    text = pluralStringResource(
                        R.plurals.library_track_count, library.tracks.size, library.tracks.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn {
                    // No item keys: with keys, tracks inserted above while scanning made the list
                    // keep its first visible key and drift down (observed during progressive scans).
                    // Rows hold no state of their own, so keys add nothing here.
                    itemsIndexed(library.tracks) { index, track ->
                        TrackRow(
                            title = track.title,
                            subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ")
                                .ifEmpty { track.relativePath.substringBeforeLast('/') },
                            isCurrent = track.id == currentMediaId,
                            leading = null,
                            onClick = { onTrackClick(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QueueTab(
    queue: QueueSnapshot,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) {
        EmptyMessage(text = stringResource(R.string.queue_empty), modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier) {
        // Only visible rows are materialized. Queue ids are not unique if a track appears twice,
        // so rows are keyed by position.
        items(count = queue.size) { index ->
            val item = queue[index]
            TrackRow(
                title = item.title,
                subtitle = null,
                isCurrent = index == currentIndex,
                leading = (index + 1).toString(),
                onClick = { onItemClick(index) },
            )
        }
    }
}

@Composable
internal fun FoldersTab(
    library: LibraryState,
    onAddFolder: () -> Unit,
    onReauthorize: (LibraryFolder) -> Unit,
    onRemove: (LibraryFolder) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<LibraryFolder?>(null) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onAddFolder) { Text(stringResource(R.string.action_add_folder)) }
            OutlinedButton(
                onClick = onRefresh,
                enabled = library.loaded && library.folders.isNotEmpty() && !library.isScanning,
            ) { Text(stringResource(R.string.action_refresh_library)) }
        }
        HorizontalDivider()
        if (library.loaded && library.folders.isEmpty()) {
            EmptyMessage(text = stringResource(R.string.folders_empty))
        }
        LazyColumn {
            items(library.folders, key = { it.key.toString() }) { folder ->
                FolderRow(
                    folder = folder,
                    onReauthorize = { onReauthorize(folder) },
                    onRemove = { pendingRemoval = folder },
                )
            }
        }
    }
    pendingRemoval?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_folder_title, folder.name)) },
            text = { Text(stringResource(R.string.remove_folder_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    onRemove(folder)
                }) { Text(stringResource(R.string.action_remove_folder)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun FolderRow(folder: LibraryFolder, onReauthorize: () -> Unit, onRemove: () -> Unit) {
    val problem = folder.status == FolderStatus.NO_PERMISSION || folder.status == FolderStatus.UNAVAILABLE
    ListItem(
        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    text = when (folder.status) {
                        FolderStatus.SCANNING -> stringResource(R.string.folder_status_scanning)
                        FolderStatus.READY -> pluralStringResource(
                            R.plurals.folder_status_ready, folder.trackCount, folder.trackCount,
                        )
                        FolderStatus.NO_PERMISSION -> stringResource(R.string.folder_status_no_permission)
                        FolderStatus.UNAVAILABLE -> stringResource(R.string.folder_status_unavailable)
                    },
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folder.skippedDirectories > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.folder_skipped_directories,
                            folder.skippedDirectories,
                            folder.skippedDirectories,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Row {
                if (folder.status == FolderStatus.NO_PERMISSION) {
                    TextButton(onClick = onReauthorize) { Text(stringResource(R.string.action_reauthorize_folder)) }
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove_folder)) }
            }
        },
    )
}

@Composable
private fun TrackRow(
    title: String,
    subtitle: String?,
    isCurrent: Boolean,
    leading: String?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title.ifBlank { stringResource(R.string.untitled_track) },
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = when {
            isCurrent -> {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = stringResource(R.string.now_playing_indicator),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            leading != null -> {
                { Text(leading, style = MaterialTheme.typography.bodySmall, modifier = Modifier.size(32.dp)) }
            }
            else -> null
        },
        colors = if (isCurrent) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyMessage(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
