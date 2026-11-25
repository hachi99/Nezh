package com.hachicorp.nezh.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hachicorp.nezh.R
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.playback.QueueSnapshot
import kotlin.math.roundToInt

/** Queue edits. Each names the snapshot the user acted on; stale edits are ignored by the player. */
internal data class QueueActions(
    val onPlay: (Int) -> Unit = {},
    val onRemove: (QueueSnapshot, Int) -> Unit = { _, _ -> },
    val onMove: (QueueSnapshot, Int, Int) -> Unit = { _, _, _ -> },
    val onClearUpcoming: (QueueSnapshot) -> Unit = {},
)

/**
 * An upcoming item being dragged: [from] is its queue index in [queue], the snapshot shown when
 * the drag began; [dy] the drag distance in pixels.
 */
@Stable
private class QueueDrag(val from: Int, val queue: QueueSnapshot) {
    var dy by mutableFloatStateOf(0f)
    /** Pointer position in the list, for scrolling while dragging near an edge. */
    var pointerY by mutableFloatStateOf(0f)
}

private const val KEY_PREVIOUS = "previous"
private const val KEY_NOW = "now"
private const val KEY_UPCOMING = "upcoming"

/**
 * The queue in playback order: earlier tracks, the current one, and the upcoming ones.
 *
 * - Upcoming tracks can be removed, reordered (long press and drag; move up/down for TalkBack)
 *   and cleared. Earlier tracks can be removed. The current track is never removed or moved here,
 *   so these edits never interrupt playback.
 * - Opens scrolled to the current track.
 * - Rows are keyed by queue position (a track may be queued twice); only visible rows are built.
 */
@Composable
internal fun QueueTab(
    queue: QueueSnapshot,
    currentIndex: Int,
    trackInfo: (String) -> Track?,
    actions: QueueActions,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) {
        EmptyMessage(text = stringResource(R.string.queue_empty), modifier = modifier)
        return
    }
    // Rows follow the play order: the queue order, or the shuffle order when shuffle is on. List
    // positions below are play positions; order[position] is the queue index of that row.
    val order = queue.playOrder
    val shuffled = queue.shuffled
    val currentPos = if (currentIndex >= 0) order.indexOf(currentIndex) else -1
    val previousCount = currentPos.coerceAtLeast(0)
    val firstUpcoming = currentPos + 1 // 0 when there is no current item
    val upcomingCount = (queue.size - firstUpcoming).coerceAtLeast(0)
    // Reordering moves tracks in the queue; with shuffle on that would not change what plays next.
    val canReorder = !shuffled && upcomingCount > 1
    val rowPx = with(LocalDensity.current) { TrackRowHeight.toPx() }
    val haptics = LocalHapticFeedback.current

    // Item 0 of the list is the "Anteriores" header when there are earlier tracks.
    val nowHeaderIndex = if (previousCount > 0) previousCount + 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (currentIndex >= 0) nowHeaderIndex else 0)

    var drag by remember { mutableStateOf<QueueDrag?>(null) }
    // A queue change or a track change ends a drag: its indices may no longer be valid. Until this
    // runs (after the frame), [positions] already ignores a drag that does not match the queue.
    LaunchedEffect(queue, currentIndex) { drag = null }
    var confirmClear by remember { mutableStateOf(false) }
    // After clearing, the shorter list would otherwise settle wherever it fits, often away from
    // the current track; bring the current track back into view once the cleared queue arrives.
    var showCurrentAfterClear by remember { mutableStateOf(false) }
    LaunchedEffect(queue) {
        if (showCurrentAfterClear) {
            showCurrentAfterClear = false
            if (upcomingCount == 0 && currentIndex >= 0) listState.scrollToItem(nowHeaderIndex)
        }
    }

    /** Where [d] is and would drop on the queue shown, or null if it no longer applies to it. */
    fun positions(d: QueueDrag): DragPositions? =
        dragPositions(d.queue, queue, d.from, d.dy, rowPx, firstUpcoming, queue.size)

    /** Queue index shown at upcoming position [slot] while an item is dragged over the list. */
    fun sourceAt(slot: Int): Int {
        val p = drag?.let(::positions) ?: return slot
        return dragSourceAt(slot, p.from, p.target)
    }

    // Scrolls while an item is held near the top or bottom edge, so it can travel the whole queue.
    LaunchedEffect(drag) {
        val d = drag ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val height = listState.layoutInfo.viewportSize.height.toFloat()
            val speed = when {
                d.pointerY < rowPx -> -(rowPx - d.pointerY) / rowPx * MAX_SCROLL_PX_PER_FRAME
                d.pointerY > height - rowPx -> (d.pointerY - (height - rowPx)) / rowPx * MAX_SCROLL_PX_PER_FRAME
                else -> 0f
            }
            if (speed != 0f) d.dy += listState.scrollBy(speed)
        }
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = drag == null,
        modifier = modifier.pointerInput(queue, firstUpcoming, rowPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val index = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { offset.y.toInt() in it.offset until it.offset + it.size }
                        ?.key as? Int
                    if (index != null && index >= firstUpcoming && canReorder) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag = QueueDrag(index, queue).also { it.pointerY = offset.y }
                    }
                },
                onDrag = { change, amount ->
                    drag?.let {
                        change.consume()
                        it.dy += amount.y
                        it.pointerY = change.position.y
                    }
                },
                onDragEnd = {
                    drag?.let(::positions)?.let { p ->
                        if (p.target != p.from) actions.onMove(queue, p.from, p.target)
                    }
                    drag = null
                },
                onDragCancel = { drag = null },
            )
        },
    ) {
        if (previousCount > 0) {
            item(key = KEY_PREVIOUS, contentType = "section") {
                SectionHeader(stringResource(R.string.queue_previous))
            }
            items(count = previousCount, key = { it }, contentType = { "track" }) { position ->
                val index = order[position]
                QueueRow(
                    queue = queue,
                    index = index,
                    isCurrent = false,
                    trackInfo = trackInfo,
                    // Not while dragging: a long press that did not move must not also play the row.
                    onClick = { if (drag == null) actions.onPlay(index) },
                    onRemove = { actions.onRemove(queue, index) },
                )
            }
        }
        if (currentIndex >= 0) {
            item(key = KEY_NOW, contentType = "section") {
                SectionHeader(stringResource(R.string.queue_now_playing))
            }
            item(key = currentPos, contentType = "track") {
                QueueRow(
                    queue = queue,
                    index = currentIndex,
                    isCurrent = true,
                    trackInfo = trackInfo,
                    onClick = { if (drag == null) actions.onPlay(currentIndex) },
                )
            }
        }
        item(key = KEY_UPCOMING, contentType = "section") {
            SectionHeader(pluralStringResource(R.plurals.queue_upcoming, upcomingCount, upcomingCount)) {
                TextButton(onClick = { confirmClear = true }, enabled = upcomingCount > 0) {
                    Text(stringResource(R.string.action_clear_upcoming))
                }
            }
        }
        if (shuffled && upcomingCount > 0) {
            item(contentType = "note") {
                Text(
                    text = stringResource(R.string.queue_shuffled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (upcomingCount == 0) {
            item(contentType = "empty") {
                Text(
                    text = stringResource(R.string.queue_upcoming_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(count = upcomingCount, key = { firstUpcoming + it }, contentType = { "track" }) { offset ->
            val slot = firstUpcoming + offset
            val index = if (shuffled) order[slot] else sourceAt(slot)
            val dragging = drag?.takeIf { it.from == index && positions(it) != null }
            QueueRow(
                queue = queue,
                index = index,
                isCurrent = false,
                trackInfo = trackInfo,
                onClick = { if (drag == null) actions.onPlay(index) },
                onRemove = { actions.onRemove(queue, index) },
                onMoveUp = if (canReorder && index > firstUpcoming) ({ actions.onMove(queue, index, index - 1) }) else null,
                onMoveDown = if (canReorder && index < queue.size - 1) ({ actions.onMove(queue, index, index + 1) }) else null,
                reorderable = canReorder,
                dragged = dragging != null,
                modifier = if (dragging != null) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            val p = positions(dragging)
                            translationY = if (p == null) 0f else dragging.dy - (p.target - p.from) * rowPx
                        }
                } else {
                    Modifier
                },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_upcoming_title)) },
            text = { Text(stringResource(R.string.clear_upcoming_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    showCurrentAfterClear = true
                    actions.onClearUpcoming(queue)
                }) { Text(stringResource(R.string.action_clear_upcoming)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Position the item dragged from queue index [from] would drop at, after moving [dy] pixels with
 * rows [rowPx] tall: whole rows, kept within the upcoming tracks [firstUpcoming, size).
 * Indices are queue indices; they equal play positions because reordering is off with shuffle.
 */
internal fun dragTarget(from: Int, dy: Float, rowPx: Float, firstUpcoming: Int, size: Int): Int =
    (from + (dy / rowPx).roundToInt()).coerceIn(firstUpcoming, size - 1)

/** Source and drop position of a drag that still applies to the queue shown. */
internal data class DragPositions(val from: Int, val target: Int)

/**
 * The drag of queue index [from], begun on the snapshot [startedOn], as positions in [current];
 * null when it no longer applies: the queue changed since it began (indices may now point to other
 * tracks or past the end), or [from] is not an upcoming track any more. The target is clamped to
 * the upcoming tracks, so both positions are always valid indices of [current].
 */
internal fun dragPositions(
    startedOn: Any,
    current: Any,
    from: Int,
    dy: Float,
    rowPx: Float,
    firstUpcoming: Int,
    size: Int,
): DragPositions? {
    if (startedOn !== current || from !in firstUpcoming until size) return null
    return DragPositions(from, dragTarget(from, dy, rowPx, firstUpcoming, size))
}

/**
 * Queue index shown at position [slot] while the item at [from] is held over [target]: the held
 * item at the target, the rows in between shifted by one toward the gap it left, others unchanged.
 */
internal fun dragSourceAt(slot: Int, from: Int, target: Int): Int = when {
    slot == target -> from
    from < target && slot in from until target -> slot + 1
    from > target && slot in target + 1..from -> slot - 1
    else -> slot
}

@Composable
private fun QueueRow(
    queue: QueueSnapshot,
    index: Int,
    isCurrent: Boolean,
    trackInfo: (String) -> Track?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    reorderable: Boolean = false,
    dragged: Boolean = false,
) {
    val item = queue[index]
    val track = trackInfo(item.id)
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    TrackRow(
        title = item.title,
        subtitle = track?.subtitle(),
        artworkUri = track?.artworkUri ?: item.artworkUri,
        isCurrent = isCurrent,
        onClick = onClick,
        background = when {
            dragged -> MaterialTheme.colorScheme.surfaceContainerHighest
            isCurrent -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        modifier = modifier.semantics {
            customActions = listOfNotNull(
                onMoveUp?.let { CustomAccessibilityAction(moveUp) { it(); true } },
                onMoveDown?.let { CustomAccessibilityAction(moveDown) { it(); true } },
            )
        },
    ) {
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_remove_from_queue, item.title),
                )
            }
        }
        if (reorderable) {
            // A hint only: the drag starts with a long press anywhere on the row.
            Box(modifier = Modifier.size(48.dp).padding(12.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.action_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_SCROLL_PX_PER_FRAME = 24f
