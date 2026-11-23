package com.hachicorp.nezh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.playback.ConnectionStatus
import com.hachicorp.nezh.playback.PlaybackErrorKind
import com.hachicorp.nezh.playback.PlaybackErrorReport
import com.hachicorp.nezh.playback.PlaybackUiState
import com.hachicorp.nezh.playback.QueueItem
import com.hachicorp.nezh.playback.QueueSnapshot
import com.hachicorp.nezh.ui.theme.NezhTheme

/** User actions, kept together so the screen stays stateless. */
data class PlayerActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onQueueItemClick: (Int) -> Unit = {},
    val onTrackClick: (Int) -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onReauthorizeFolder: (LibraryFolder) -> Unit = {},
    val onRemoveFolder: (LibraryFolder) -> Unit = {},
    val onRefreshLibrary: () -> Unit = {},
    val onRetryConnection: () -> Unit = {},
)

private enum class PlayerTab(val label: Int) {
    LIBRARY(R.string.tab_library),
    QUEUE(R.string.tab_queue),
    FOLDERS(R.string.tab_folders),
}

@Composable
fun PlayerScreen(
    playback: PlaybackUiState,
    library: LibraryState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(PlayerTab.LIBRARY.ordinal) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            NowPlaying(playback)
            PlaybackMessages(playback, onRetryConnection = actions.onRetryConnection)
            Spacer(Modifier.height(4.dp))
            PlaybackControls(
                showPlayButton = playback.showPlayButton,
                enabled = playback.connection == ConnectionStatus.CONNECTED && playback.queue.isNotEmpty(),
                actions = actions,
            )
        }
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            PlayerTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab.ordinal,
                    onClick = { selectedTab = tab.ordinal },
                    text = { Text(stringResource(tab.label)) },
                )
            }
        }
        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        when (PlayerTab.entries[selectedTab]) {
            PlayerTab.LIBRARY -> LibraryTab(
                library = library,
                currentMediaId = playback.currentMediaId,
                onTrackClick = actions.onTrackClick,
                onAddFolder = actions.onAddFolder,
                modifier = contentModifier,
            )
            PlayerTab.QUEUE -> QueueTab(
                queue = playback.queue,
                currentIndex = playback.currentIndex,
                onItemClick = actions.onQueueItemClick,
                modifier = contentModifier,
            )
            PlayerTab.FOLDERS -> FoldersTab(
                library = library,
                onAddFolder = actions.onAddFolder,
                onReauthorize = actions.onReauthorizeFolder,
                onRemove = actions.onRemoveFolder,
                onRefresh = actions.onRefreshLibrary,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun NowPlaying(state: PlaybackUiState) {
    Text(
        text = state.currentTitle?.ifBlank { null }
            ?: stringResource(if (state.currentIndex >= 0) R.string.untitled_track else R.string.nothing_playing),
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val details = listOfNotNull(
        state.currentArtist?.takeIf { it.isNotBlank() },
        state.currentAlbum?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    if (details.isNotEmpty()) {
        Text(
            text = details,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaybackMessages(state: PlaybackUiState, onRetryConnection: () -> Unit) {
    if (state.connection == ConnectionStatus.UNAVAILABLE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ErrorText(stringResource(R.string.service_unavailable), Modifier.weight(1f))
            TextButton(onClick = onRetryConnection) { Text(stringResource(R.string.action_retry)) }
        }
    }
    state.error?.let { ErrorText(errorMessage(it)) }
    if (state.skippedCount > 0) {
        ErrorText(
            pluralStringResource(
                R.plurals.playback_skipped,
                state.skippedCount,
                state.skippedCount,
                state.lastSkippedTitle.orEmpty(),
            )
        )
    }
}

@Composable
private fun errorMessage(report: PlaybackErrorReport): String {
    val title = report.title.ifBlank { stringResource(R.string.untitled_track) }
    return when (report.kind) {
        PlaybackErrorKind.FILE -> stringResource(R.string.error_file, title)
        PlaybackErrorKind.PERMISSION -> stringResource(R.string.error_permission, title)
        PlaybackErrorKind.OTHER -> stringResource(R.string.error_other)
    }
}

@Composable
private fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PlaybackControls(showPlayButton: Boolean, enabled: Boolean, actions: PlayerActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onPrevious, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.action_previous),
            )
        }
        FilledIconButton(onClick = actions.onPlayPause, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(if (showPlayButton) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = stringResource(
                    if (showPlayButton) R.string.action_play else R.string.action_pause
                ),
            )
        }
        IconButton(onClick = actions.onNext, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.action_next),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayerScreenPreview() {
    NezhTheme {
        PlayerScreen(
            playback = PlaybackUiState(
                connection = ConnectionStatus.CONNECTED,
                queue = QueueSnapshot.of(listOf(QueueItem("1", "Primera canción"), QueueItem("2", "Segunda canción"))),
                currentIndex = 0,
                currentMediaId = "1",
                currentTitle = "Primera canción",
                currentArtist = "Artista",
                showPlayButton = false,
                skippedCount = 1,
                lastSkippedTitle = "archivo_roto",
            ),
            library = LibraryState(loaded = true),
            actions = PlayerActions(),
        )
    }
}
