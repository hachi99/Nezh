package com.hachicorp.nezh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.playback.ConnectionStatus
import com.hachicorp.nezh.playback.PlaybackErrorKind
import com.hachicorp.nezh.playback.PlaybackErrorReport
import com.hachicorp.nezh.playback.PlaybackErrorStage
import com.hachicorp.nezh.playback.PlaybackUiState
import com.hachicorp.nezh.playback.QueueItem
import com.hachicorp.nezh.playback.QueueSnapshot
import com.hachicorp.nezh.ui.theme.NezhTheme

/** User actions, kept together so the screen stays stateless. */
data class PlayerActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onToggleShuffle: () -> Unit = {},
    val onCycleRepeat: () -> Unit = {},
    val onQueueItemClick: (Int) -> Unit = {},
    val onTrackClick: (Int) -> Unit = {},
    val onAddToQueue: (Track) -> Unit = {},
    val onRemoveFromQueue: (QueueSnapshot, Int) -> Unit = { _, _ -> },
    val onMoveInQueue: (QueueSnapshot, Int, Int) -> Unit = { _, _, _ -> },
    val onClearUpcoming: (QueueSnapshot) -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onReauthorizeFolder: (LibraryFolder) -> Unit = {},
    val onRemoveFolder: (LibraryFolder) -> Unit = {},
    val onRefreshLibrary: () -> Unit = {},
    val onRetryConnection: () -> Unit = {},
)

private enum class PlayerTab(val label: Int) {
    LIBRARY(R.string.tab_library),
    FOLDERS(R.string.tab_folders),
}

/**
 * Hierarchy: Library (with the playback bar) -> Now Playing -> queue sheet. Closing Now Playing
 * only changes what is shown; playback, the notification and external controls are unaffected.
 */
@Composable
fun PlayerScreen(
    playback: PlaybackUiState,
    library: LibraryState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    nowPlayingSupport: NowPlayingSupport = NowPlayingSupport(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(PlayerTab.LIBRARY.ordinal) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    val hasQueue = playback.connection == ConnectionStatus.CONNECTED && playback.queue.isNotEmpty()
    // Nothing to show once the queue is gone (e.g. its last item was removed).
    if (!hasQueue && nowPlayingOpen && playback.connection == ConnectionStatus.CONNECTED) nowPlayingOpen = false
    BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }
    Box(modifier = modifier.fillMaxSize()) {
        // While Now Playing covers it, the library is hidden from accessibility services.
        Column(modifier = Modifier.fillMaxSize().then(if (nowPlayingOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasQueue) {
                    TextButton(onClick = { nowPlayingOpen = true }) {
                        Text(stringResource(R.string.action_now_playing))
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                PlaybackMessages(playback, onRetryConnection = actions.onRetryConnection)
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
                    onAddToQueue = actions.onAddToQueue,
                    onAddFolder = actions.onAddFolder,
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
            if (hasQueue) MiniPlayerBar(playback, actions, onOpen = { nowPlayingOpen = true })
        }
        AnimatedVisibility(
            visible = nowPlayingOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            NowPlayingScreen(
                playback = playback,
                library = library,
                actions = actions,
                support = nowPlayingSupport,
                onClose = { nowPlayingOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
                state.lastSkipped?.let { reasonText(it) }.orEmpty(),
            )
        )
    }
}

@Composable
private fun errorMessage(report: PlaybackErrorReport): String {
    val title = titleOrUntitled(report.title)
    return when (report.kind) {
        PlaybackErrorKind.FILE -> stringResource(R.string.error_file, title, reasonText(report))
        PlaybackErrorKind.PERMISSION -> stringResource(R.string.error_permission, title)
        PlaybackErrorKind.OTHER -> stringResource(R.string.error_other)
    }
}

/** "formato o contenedor no compatible (ERROR_CODE_…)": the stage in words, plus the exact code. */
@Composable
private fun reasonText(report: PlaybackErrorReport): String {
    val stage = stringResource(
        when (report.stage) {
            PlaybackErrorStage.READ_FILE -> R.string.error_stage_read
            PlaybackErrorStage.CONTAINER -> R.string.error_stage_container
            PlaybackErrorStage.DECODER -> R.string.error_stage_decoder
            PlaybackErrorStage.OTHER -> R.string.error_stage_other
        }
    )
    return if (report.code.isEmpty()) stage else "$stage (${report.code})"
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

/** Shuffle: dimmed when off, accent color when on; the state is announced. */
@Composable
internal fun ShuffleButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (on) R.string.shuffle_on else R.string.shuffle_off)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { stateDescription = state }) {
        Icon(
            painter = painterResource(R.drawable.ic_shuffle),
            contentDescription = stringResource(R.string.action_shuffle),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** Repeat: dimmed when off; accent "repeat" for the whole queue; accent "repeat one" for a track. */
@Composable
internal fun RepeatButton(mode: Int, enabled: Boolean, onClick: () -> Unit) {
    val state = stringResource(
        when (mode) {
            Player.REPEAT_MODE_ALL -> R.string.repeat_all
            Player.REPEAT_MODE_ONE -> R.string.repeat_one
            else -> R.string.repeat_off
        }
    )
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { stateDescription = state }) {
        Icon(
            painter = painterResource(if (mode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
            contentDescription = stringResource(R.string.action_repeat),
            tint = if (mode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
        )
    }
}
