package com.hachicorp.nezh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.FolderStatus
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.library.Track

/** Height of every track row in the library and the queue (uniform rows keep reordering simple). */
internal val TrackRowHeight = 64.dp

@Composable
internal fun LibraryTab(
    library: LibraryState,
    currentMediaId: String?,
    onTrackClick: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
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
                LibraryHeader(trackCount = library.tracks.size)
                LibrarySongList(
                    library = library,
                    currentMediaId = currentMediaId,
                    onTrackClick = onTrackClick,
                    onAddToQueue = onAddToQueue,
                    listState = listState,
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(trackCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.library_track_count, trackCount, trackCount),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        val sortDescription = stringResource(R.string.library_sorted_by_title_description)
        Text(
            text = stringResource(R.string.library_sort_by_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = sortDescription },
        )
    }
}

/** Artist · album, or the folder the file is in when the tags have neither. */
internal fun Track.subtitle(): String =
    listOfNotNull(artist, album).joinToString(" · ").ifEmpty { relativePath.substringBeforeLast('/') }

@Composable
internal fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * One track: cover, title and subtitle, with optional trailing actions. The current track is
 * marked with a tinted background and a bold, primary-colored title, and announced as such.
 */
@Composable
internal fun TrackRow(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val nowPlaying = if (isCurrent) stringResource(R.string.now_playing_indicator) else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { if (nowPlaying != null) stateDescription = nowPlaying }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(artworkUri = artworkUri, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { stringResource(R.string.untitled_track) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
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
internal fun EmptyMessage(
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
