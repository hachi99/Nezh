package com.hachicorp.nezh.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.playback.PlaybackUiState
import com.hachicorp.nezh.playback.TechnicalInfo
import com.hachicorp.nezh.playback.duration
import kotlinx.coroutines.delay

/** Player access that Now Playing needs beyond the state (position, lyrics, technical info). */
data class NowPlayingSupport(
    val positionMs: () -> Long = { 0L },
    val durationMs: () -> Long = { 0L },
    val seekTo: (Long) -> Unit = {},
    val currentDocument: () -> Uri? = { null },
    /** Lyrics embedded in the file (null if none). Called off the main thread by the caller. */
    val loadLyrics: suspend (Uri) -> String? = { null },
    val technicalInfo: suspend () -> TechnicalInfo = { TechnicalInfo(emptyList(), emptyList()) },
)

/** Secondary line: "artist · album", skipping what is unknown. */
internal fun artistAlbum(state: PlaybackUiState): String =
    listOfNotNull(state.currentArtist?.takeIf { it.isNotBlank() }, state.currentAlbum?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** Cover of the current track: the library's cached cover, else the queue item's. */
internal fun currentArtwork(playback: PlaybackUiState, library: LibraryState): String? =
    playback.currentMediaId?.let { library.tracksById[it]?.artworkUri }
        ?: playback.currentIndex.takeIf { it in 0 until playback.queue.size }?.let { playback.queue[it].artworkUri }

/**
 * The bottom playback bar: title, "artist · album", previous / play-pause / next in the accent
 * color. No background, card or shadow: a hairline separates it from the content. Tapping the text
 * or swiping it from right to left opens Now Playing (an alternative to the Now Playing button; it
 * does not compete with the list's scrolling, the alphabet index or the system back gesture).
 */
@Composable
internal fun MiniPlayerBar(playback: PlaybackUiState, actions: PlayerActions, onOpen: () -> Unit) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val open = stringResource(R.string.action_open_now_playing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx < -threshold) onOpen() },
                    onDragCancel = { dx = 0f },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(onClickLabel = open, onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = titleOrUntitled(playback.currentTitle),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = artistAlbum(playback)
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val accent = MaterialTheme.colorScheme.primary
        IconButton(onClick = actions.onPrevious) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.action_previous), tint = accent)
        }
        IconButton(onClick = actions.onPlayPause) {
            Icon(
                painterResource(if (playback.showPlayButton) R.drawable.ic_play else R.drawable.ic_pause),
                stringResource(if (playback.showPlayButton) R.string.action_play else R.string.action_pause),
                tint = accent,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = actions.onNext, modifier = Modifier.padding(end = 4.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.action_next), tint = accent)
        }
    }
}

/**
 * Now Playing: the cover as the main element (tap it for the lyrics), title / artist / album,
 * position, transport with shuffle and repeat, a discreet technical-information button, and the
 * real player queue in a bottom sheet (drag it up to open, down to close).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    playback: PlaybackUiState,
    library: LibraryState,
    actions: PlayerActions,
    support: NowPlayingSupport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded))
    var showInfo by rememberSaveable { mutableStateOf(false) }
    BottomSheetScaffold(
        scaffoldState = sheet,
        // The sheet must not draw outside this screen (e.g. under the navigation bar).
        // Touches on empty areas must not reach the library underneath.
        modifier = modifier.clipToBounds().pointerInput(Unit) { detectTapGestures { } },
        sheetPeekHeight = 56.dp,
        sheetShape = RectangleShape,
        sheetShadowElevation = 0.dp,
        sheetTonalElevation = 0.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetDragHandle = { QueueSheetHandle(playback) },
        sheetContent = {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            QueueTab(
                queue = playback.queue,
                currentIndex = playback.currentIndex,
                trackInfo = { id -> library.tracksById[id] },
                actions = QueueActions(
                    onPlay = actions.onQueueItemClick,
                    onRemove = actions.onRemoveFromQueue,
                    onMove = actions.onMoveInQueue,
                    onClearUpcoming = actions.onClearUpcoming,
                ),
                modifier = Modifier.fillMaxWidth().fillMaxSize(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NowPlayingContent(
            playback = playback,
            artworkUri = currentArtwork(playback, library),
            actions = actions,
            support = support,
            onClose = onClose,
            onInfo = { showInfo = true },
            modifier = Modifier.padding(padding),
        )
    }
    if (showInfo) TechnicalInfoSheet(playback.currentMediaId, support, onDismiss = { showInfo = false })
}

/** The sheet's visible strip while closed: a grip, "Cola · N", and the shuffle / repeat state. */
@Composable
private fun QueueSheetHandle(playback: PlaybackUiState) {
    Column(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .align(Alignment.CenterHorizontally),
        )
        Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_sheet_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                pluralStringResource(R.plurals.library_track_count, playback.queue.size, playback.queue.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val modes = listOfNotNull(
                stringResource(R.string.action_shuffle).takeIf { playback.shuffleEnabled },
                when (playback.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> stringResource(R.string.action_repeat) + ": " + stringResource(R.string.repeat_all)
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> stringResource(R.string.action_repeat) + ": " + stringResource(R.string.repeat_one)
                    else -> null
                },
            ).joinToString(" · ")
            if (modes.isNotEmpty()) Text(modes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NowPlayingContent(
    playback: PlaybackUiState,
    artworkUri: String?,
    actions: PlayerActions,
    support: NowPlayingSupport,
    onClose: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasTrack = playback.currentIndex >= 0
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.padding(start = 0.dp)) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.action_back_to_library))
            }
            Text(
                stringResource(R.string.now_playing_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onInfo, enabled = hasTrack) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    stringResource(R.string.action_technical_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        CoverWithLyrics(
            mediaId = playback.currentMediaId,
            artworkUri = artworkUri,
            support = support,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            // "Nothing playing" is its own state (no track), not the untitled fallback.
            text = if (hasTrack || !playback.currentTitle.isNullOrBlank()) titleOrUntitled(playback.currentTitle) else stringResource(R.string.nothing_playing),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        playback.currentArtist?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        playback.currentAlbum?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        PositionBar(playback, support)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = hasTrack
            ShuffleButton(playback.shuffleEnabled, enabled, actions.onToggleShuffle)
            IconButton(onClick = actions.onPrevious, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.action_previous), modifier = Modifier.size(32.dp))
            }
            FilledIconButton(onClick = actions.onPlayPause, enabled = enabled, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(if (playback.showPlayButton) R.drawable.ic_play else R.drawable.ic_pause),
                    stringResource(if (playback.showPlayButton) R.string.action_play else R.string.action_pause),
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = actions.onNext, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.action_next), modifier = Modifier.size(32.dp))
            }
            RepeatButton(playback.repeatMode, enabled, actions.onCycleRepeat)
        }
    }
}

/**
 * The cover, as large as fits. Tapping it shows the lyrics embedded in the file over a darkened
 * cover (white text, scrollable); tapping again or the close button hides them. Lyrics are read on
 * demand, off the main thread, and never affect playback.
 */
@Composable
private fun CoverWithLyrics(mediaId: String?, artworkUri: String?, support: NowPlayingSupport, modifier: Modifier) {
    var showLyrics by rememberSaveable(mediaId) { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val toggle = stringResource(if (showLyrics) R.string.lyrics_hide else R.string.lyrics_show)
        Box(
            modifier = Modifier
                .size(side)
                .clickable(enabled = mediaId != null) { showLyrics = !showLyrics }
                .semantics { contentDescription = toggle },
            contentAlignment = Alignment.Center,
        ) {
            ArtworkImage(artworkUri = artworkUri, size = side)
            if (artworkUri == null && !showLyrics) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(side / 3),
                )
            }
            if (showLyrics && mediaId != null) LyricsOverlay(mediaId, support, onClose = { showLyrics = false })
        }
    }
}

private sealed interface LyricsState {
    data object Loading : LyricsState
    data class Loaded(val text: String?) : LyricsState
}

@Composable
private fun LyricsOverlay(mediaId: String, support: NowPlayingSupport, onClose: () -> Unit) {
    val lyrics by produceState<LyricsState>(LyricsState.Loading, mediaId) {
        value = LyricsState.Loaded(support.currentDocument()?.let { support.loadLyrics(it) })
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f))) {
        when (val state = lyrics) {
            LyricsState.Loading -> Unit
            is LyricsState.Loaded -> {
                val text = state.text
                if (text == null) {
                    Text(
                        stringResource(R.string.lyrics_empty),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    Text(
                        text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 48.dp),
                    )
                }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.lyrics_hide), tint = Color.White)
        }
    }
}

/** Elapsed / remaining time and a seek slider; the position is read from the player while shown. */
@Composable
private fun PositionBar(playback: PlaybackUiState, support: NowPlayingSupport) {
    var position by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playback.currentMediaId, playback.showPlayButton) {
        while (true) {
            if (!dragging) position = support.positionMs()
            durationMs = support.durationMs()
            delay(250)
        }
    }
    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seekLabel = stringResource(R.string.seek_description)
    Slider(
        value = if (dragging) dragValue else fraction,
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = {
            if (durationMs > 0) {
                val target = (dragValue * durationMs).toLong()
                support.seekTo(target)
                position = target
            }
            dragging = false
        },
        enabled = durationMs > 0,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel },
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        val shown = if (dragging) (dragValue * durationMs).toLong() else position
        Text(duration(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(if (durationMs > 0) "-" + duration(durationMs - shown) else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The technical information panel: shown only on request, read for the current track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechnicalInfoSheet(mediaId: String?, support: NowPlayingSupport, onDismiss: () -> Unit) {
    val info by produceState<TechnicalInfo?>(null, mediaId) { value = support.technicalInfo() }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RectangleShape, tonalElevation = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.action_technical_info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val current = info
            when {
                current == null -> Unit
                current.isEmpty -> Text(stringResource(R.string.tech_unavailable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                else -> {
                    InfoGroup(stringResource(R.string.tech_stream_header), current.stream)
                    InfoGroup(stringResource(R.string.tech_file_header), current.file)
                }
            }
        }
    }
}

@Composable
private fun InfoGroup(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    rows.forEach { (label, value) ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
        }
    }
}
