package com.hachicorp.nezh.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackSection
import kotlinx.coroutines.launch

/** Height of a song row; the cover fills it, edge to edge. */
internal val SongRowHeight = 56.dp
/** Width kept free on the right of every row for the alphabet index. */
private val IndexWidth = 24.dp
/** The index, in list order: A–Z, then # (digits, symbols, other scripts). */
internal val IndexLabels: List<String> = ('A'..'Z').map { it.toString() } + TrackSection.OTHER

/**
 * Position of each letter's header in the song list, where every section is its header followed by
 * its tracks: the header of section i comes after i headers and `start` tracks.
 */
internal fun sectionHeaderPositions(sections: List<TrackSection>): Map<String, Int> =
    sections.mapIndexed { i, section -> section.label to section.start + i }.toMap()

/** The index letter under [y] in an index [height] pixels tall (positions outside are clamped). */
internal fun indexLabelAt(y: Float, height: Int): String {
    if (height <= 0) return IndexLabels.first()
    val i = (y / height * IndexLabels.size).toInt().coerceIn(0, IndexLabels.lastIndex)
    return IndexLabels[i]
}

/**
 * The song list: one continuous surface of compact rows (cover flush left, title, artist · album),
 * hairline separators, a compact letter header before each group, and an alphabet index on the
 * right edge to jump between letters by tapping or sliding.
 *
 * Rows have no item keys on purpose (see LibraryTab): during progressive scans keyed rows made the
 * list drift. Sections are index ranges into [LibraryState.tracks]; nothing is copied.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySongList(
    library: LibraryState,
    currentMediaId: String?,
    onTrackClick: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // Id of the track whose menu is open; kept here so rows stay light and the menu follows its track.
    var menuFor by remember { mutableStateOf<String?>(null) }
    // List position of each letter's header: headers before it + tracks before it.
    val headerPositions = remember(library.sections) { sectionHeaderPositions(library.sections) }
    val scope = rememberCoroutineScope()
    val separator = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            library.sections.forEach { section ->
                stickyHeader(contentType = "letter") { LetterHeader(section.label) }
                items(count = section.size, contentType = { "song" }) { offset ->
                    val index = section.start + offset
                    val track = library.tracks[index]
                    SongRow(
                        track = track,
                        isCurrent = track.id == currentMediaId,
                        separator = separator,
                        onClick = { onTrackClick(index) },
                        menuOpen = menuFor == track.id,
                        onOpenMenu = { menuFor = track.id },
                        onCloseMenu = { menuFor = null },
                        onAddToQueue = onAddToQueue,
                    )
                }
            }
        }
        AlphabetIndex(
            available = headerPositions.keys,
            onSelect = { label ->
                headerPositions[label]?.let { position -> scope.launch { listState.scrollToItem(position) } }
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(IndexWidth),
        )
    }
}

/** A compact letter bar, as its own row (it sticks to the top while its group scrolls). */
@Composable
private fun LetterHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One song: square cover filling the row's height at the left edge, title (bold) over
 * artist · album (lower contrast), the options menu, and room for the index. A hairline separator
 * at the bottom; no padding, card, corner or background of its own. The current song is marked by
 * a play glyph and the title in the accent color (and announced as playing).
 */
@Composable
private fun SongRow(
    track: Track,
    isCurrent: Boolean,
    separator: Color,
    onClick: () -> Unit,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val nowPlaying = if (isCurrent) stringResource(R.string.now_playing_indicator) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SongRowHeight)
            .clickable(onClick = onClick)
            .semantics { if (nowPlaying != null) stateDescription = nowPlaying }
            .drawBehind {
                val y = size.height - 0.5f
                drawLine(separator, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            .padding(end = IndexWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(artworkUri = track.artworkUri, size = SongRowHeight)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = titleOrUntitled(track.title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = track.subtitle(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = SongRowHeight)
                    .clickable(onClick = onOpenMenu)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.action_track_options, titleOrUntitled(track.title)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (menuOpen) {
                DropdownMenu(expanded = true, onDismissRequest = onCloseMenu) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_to_queue)) },
                        onClick = {
                            onCloseMenu()
                            onAddToQueue(track)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The alphabet index on the right edge: A–Z and #, evenly spread over the list's height. Touching
 * a letter jumps to its group; sliding along the index moves from letter to letter. Letters without
 * songs are dimmed and inert. While touched, a light strip shows the index is active. Each letter is
 * also a button for accessibility services.
 */
@Composable
private fun AlphabetIndex(
    available: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(false) }
    // The gesture outlives recompositions: it must always use the current letters and positions
    // (the library grows while a folder is scanned), not those of the first composition.
    val currentAvailable by rememberUpdatedState(available)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val haptics = LocalHapticFeedback.current
    val activeStrip = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val enabledColor = MaterialTheme.colorScheme.primary
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Column(
        modifier = modifier
            // Keep the index out of the system's bottom gesture area (dragging up from "#" must not
            // send the app home) and exclude it from the edge "back" gesture where allowed.
            .windowInsetsPadding(
                WindowInsets.systemGestures.exclude(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            )
            .systemGestureExclusion()
            .padding(vertical = 4.dp)
            .background(if (active) activeStrip else Color.Transparent, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                var last: String? = null
                fun select(y: Float) {
                    val label = indexLabelAt(y, size.height)
                    if (label in currentAvailable && label != last) {
                        last = label
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnSelect(label)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    active = true
                    last = null
                    select(down.position.y)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Also on release: a quick flick ends on the letter where the finger lifts.
                        select(change.position.y)
                        change.consume()
                        if (!change.pressed) break
                    }
                    active = false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Equal slots (the same split indexLabelAt uses), and letters never taller than their slot:
        // all 27 always fit, whatever the screen height or the user's font size.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val slot = maxHeight / IndexLabels.size
            val density = LocalDensity.current
            val fontSize = with(density) { minOf(11.sp.toPx(), (slot * 0.75f).toPx()).toSp() }
            Column {
                IndexLabels.forEach { label ->
                    val enabled = label in available
                    val description = stringResource(R.string.index_jump_to, label)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(slot)
                            .semantics {
                                contentDescription = description
                                role = Role.Button
                                if (enabled) onClick { onSelect(label); true } else disabled()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = fontSize,
                            lineHeight = fontSize,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) enabledColor else disabledColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
