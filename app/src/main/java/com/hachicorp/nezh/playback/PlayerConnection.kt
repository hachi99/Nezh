package com.hachicorp.nezh.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.QueueFilter
import com.hachicorp.nezh.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

data class QueueItem(val id: String, val title: String, val artworkUri: String? = null)

/**
 * Read-only view of the queue for the UI. Backed by an immutable player [Timeline] snapshot, so a
 * playlist change costs O(1) instead of copying every item (6 full copies of a 10,000-item queue,
 * 29–81 ms each on the main thread, were measured per track tap). Items are created on demand,
 * for the rows actually shown. Equality is by snapshot: each playlist change is a new snapshot.
 */
class QueueSnapshot private constructor(
    private val timeline: Timeline?,
    private val items: List<QueueItem>?,
    /** True when the player plays this queue in shuffle order. */
    val shuffled: Boolean = false,
) {
    val size: Int = timeline?.windowCount ?: items!!.size

    /**
     * Queue indices in the order they play: the queue order, or the shuffle order when [shuffled]
     * (as the session publishes it). Computed once per snapshot, on first use.
     */
    val playOrder: IntArray by lazy {
        val t = timeline
        if (!shuffled || t == null || t.isEmpty) return@lazy IntArray(size) { it }
        val order = IntArray(size)
        var i = t.getFirstWindowIndex(true)
        var n = 0
        while (i != C.INDEX_UNSET && n < size) {
            order[n++] = i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        if (n == size) order else IntArray(size) { it } // defensive: never lose an entry
    }

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size > 0

    operator fun get(index: Int): QueueItem {
        items?.let { return it[index] }
        val item = timeline!!.getWindow(index, Timeline.Window()).mediaItem
        return QueueItem(
            id = item.mediaId,
            title = item.displayTitle().orEmpty(),
            artworkUri = item.mediaMetadata.artworkUri?.toString(),
        )
    }

    companion object {
        val EMPTY = QueueSnapshot(null, emptyList())
        internal fun of(timeline: Timeline, shuffled: Boolean = false) = QueueSnapshot(timeline, null, shuffled)
        /** For previews and tests. */
        fun of(items: List<QueueItem>) = QueueSnapshot(null, items)
    }
}

enum class ConnectionStatus { CONNECTING, CONNECTED, UNAVAILABLE }

data class PlaybackUiState(
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val queue: QueueSnapshot = QueueSnapshot.EMPTY,
    /** Index of the current item in [queue], or -1 when the queue is empty. */
    val currentIndex: Int = -1,
    val currentMediaId: String? = null,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentAlbum: String? = null,
    val showPlayButton: Boolean = true,
    /** Shuffle is on: the queue plays in [QueueSnapshot.playOrder]. */
    val shuffleEnabled: Boolean = false,
    /** Player.REPEAT_MODE_OFF, _ALL or _ONE. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Files skipped because they could not be played, since the last user action. */
    val skippedCount: Int = 0,
    /** Title of the most recently skipped file, when [skippedCount] > 0. */
    val lastSkippedTitle: String? = null,
    /** Why the most recently skipped file could not be played (stage and Media3 code). */
    val lastSkipped: PlaybackErrorReport? = null,
    /** The error that stopped playback, until the user acts or playback resumes. */
    val error: PlaybackErrorReport? = null,
)

/**
 * Connects the UI to [PlaybackService] through a [MediaController] and exposes the player state.
 *
 * Must be used from the main thread. Call [connect] in `onStart` and [disconnect] in `onStop`.
 * A connection that completes after [disconnect] is released and never used; if the service
 * disconnects on its own, the controller is dropped and [PlaybackUiState.connection] becomes
 * [ConnectionStatus.UNAVAILABLE] until [connect] is called again.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Non-null while connecting or connected. Identifies the connection we own. */
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Latest "play these tracks" request made while not connected (B4). */
    private var pendingPlay: Pair<List<MediaItem>, Int>? = null
    // Count + last title only: copying a growing list on every skip was O(n²) (UI jank measured
    // with a queue of thousands of broken files).
    private var skippedCount = 0
    private var lastSkippedTitle: String? = null
    private var lastSkipped: PlaybackErrorReport? = null
    private var error: PlaybackErrorReport? = null
    private var cachedQueue: QueueSnapshot = QueueSnapshot.EMPTY
    /** When the user last asked to play tracks, until playback starts (performance log only). */
    private var playRequestedAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // A new queue snapshot is only needed when the playlist changed (B7).
            // The play order shown also changes when shuffle is turned on or off.
            val queueChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            if (player.isPlaying) error = null
            // A stop message is about one item: once another item is current, it no longer applies
            // (e.g. "next" from Bluetooth or the lock screen). Matched by id, not by the event
            // alone: the message and the transition to its own item can arrive in either order.
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && error?.mediaId != player.currentMediaItem?.mediaId) {
                error = null
            }
            if (player.isPlaying && playRequestedAt != 0L) {
                Log.i(PERF_TAG, "playStart afterMs=${SystemClock.elapsedRealtime() - playRequestedAt}")
                playRequestedAt = 0L
            }
            publishState(rebuildQueue = queueChanged)
        }
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // Events can arrive before the future completes (B3): accept them for the connection
            // we own, whether or not onControllerReady has run yet.
            if (controllerFuture == null) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
            }
            when (command.customAction) {
                PlaybackService.ACTION_ITEM_SKIPPED -> {
                    skippedCount++
                    lastSkippedTitle = args.getString(PlaybackService.EXTRA_TITLE).orEmpty()
                    lastSkipped = PlaybackErrorReport.fromBundle(args)
                }
                PlaybackService.ACTION_PLAYBACK_STOPPED -> {
                    error = PlaybackErrorReport.fromBundle(args)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            publishState(rebuildQueue = false)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(controller: MediaController) {
            // Our own disconnect() clears controllerFuture first; anything else is unexpected.
            if (controllerFuture == null) return
            Log.w(TAG, "Playback service disconnected")
            this@PlayerConnection.controller?.removeListener(playerListener)
            this@PlayerConnection.controller = null
            controllerFuture?.let(MediaController::releaseFuture)
            controllerFuture = null
            _state.update { it.copy(connection = ConnectionStatus.UNAVAILABLE) }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        _state.update { it.copy(connection = ConnectionStatus.CONNECTING) }
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(sessionListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({ onControllerReady(future) }, ContextCompat.getMainExecutor(appContext))
    }

    fun disconnect() {
        val future = controllerFuture ?: return
        controllerFuture = null
        controller?.removeListener(playerListener)
        controller = null
        // Releases the controller, or cancels/releases it if the connection is still pending.
        MediaController.releaseFuture(future)
        _state.update { it.copy(connection = ConnectionStatus.CONNECTING) }
    }

    private fun onControllerReady(future: ListenableFuture<MediaController>) {
        // Disconnected (or reconnected) while this connection was pending: releaseFuture owns it.
        if (controllerFuture !== future) return
        val connected = try {
            future.get()
        } catch (e: ExecutionException) {
            Log.e(TAG, "Could not connect to the playback service", e)
            controllerFuture = null
            _state.update { it.copy(connection = ConnectionStatus.UNAVAILABLE) }
            return
        } catch (e: CancellationException) {
            Log.w(TAG, "Connection to the playback service was cancelled")
            controllerFuture = null
            _state.update { it.copy(connection = ConnectionStatus.UNAVAILABLE) }
            return
        }
        controller = connected
        connected.addListener(playerListener)
        pendingPlay?.let { (items, startIndex) ->
            pendingPlay = null
            startPlayback(connected, items, startIndex)
        }
        publishState(rebuildQueue = true)
        requestCurrentError(connected)
    }

    /** Reconnecting UIs learn about an error that happened while they were away. */
    private fun requestCurrentError(connected: MediaController) {
        val result = connected.sendCustomCommand(PlaybackService.GET_ERROR_COMMAND, Bundle.EMPTY)
        result.addListener({
            if (controller !== connected) return@addListener
            val sessionResult = try {
                result.get()
            } catch (e: ExecutionException) {
                Log.w(TAG, "Could not read the playback error state", e)
                return@addListener
            } catch (e: CancellationException) {
                return@addListener
            }
            // The service only returns a report while playback is stopped by one: a player error,
            // or a file that cannot sound (no player error, the player is paused).
            val report = if (sessionResult.resultCode == SessionResult.RESULT_SUCCESS) {
                PlaybackErrorReport.fromBundle(sessionResult.extras)
            } else null
            if (report != null) {
                error = report
                publishState(rebuildQueue = false)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * Replaces the queue with [tracks] and starts playing at [startIndex]. Call from the main
     * thread; the MediaItems are built on a background thread (193 ms for 10,000 tracks measured
     * on the main thread before), and the controller is used back on the main thread.
     */
    suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        val start = SystemClock.elapsedRealtime()
        playRequestedAt = start
        val items = withContext(Dispatchers.Default) { tracks.map { it.toMediaItem() } }
        Log.i(PERF_TAG, "playTracks items=${items.size} buildMs=${SystemClock.elapsedRealtime() - start} (background)")
        val c = controller ?: run {
            pendingPlay = items to startIndex
            return
        }
        startPlayback(c, items, startIndex)
    }

    private fun startPlayback(c: MediaController, items: List<MediaItem>, startIndex: Int) {
        clearMessages()
        val start = SystemClock.elapsedRealtime()
        c.setMediaItems(items, startIndex, C.TIME_UNSET)
        c.prepare()
        c.play()
        Log.i(PERF_TAG, "setMediaItems items=${items.size} mainMs=${SystemClock.elapsedRealtime() - start}")
    }

    @OptIn(UnstableApi::class)
    fun playPause() {
        val c = controller ?: return
        clearMessages()
        // Also prepares an idle player (e.g. retry after an error) and restarts an ended one.
        Util.handlePlayPauseButtonAction(c)
    }

    fun next() = userSeek { it.seekToNext() }

    fun previous() = userSeek { it.seekToPrevious() }

    fun playAt(index: Int) = userSeek {
        if (index in 0 until it.mediaItemCount) {
            it.seekToDefaultPosition(index)
            it.play()
        }
    }

    /**
     * Adds [track] at the end of the queue without touching what is playing: the current item,
     * its position and the order of the queued items are kept, and duplicates are allowed.
     *
     * With no active queue (empty, or finished), the track starts playing, as choosing music in
     * Nezh always does when nothing is queued. Returns false if the service is not connected.
     */
    fun addToQueue(track: Track): Boolean {
        val c = controller ?: return false
        val item = track.toMediaItem()
        when {
            c.mediaItemCount == 0 -> startPlayback(c, listOf(item), 0)
            c.playbackState == Player.STATE_ENDED -> {
                // Otherwise "play" would restart the finished last track instead of the new one.
                c.addMediaItem(item)
                c.seekToDefaultPosition(c.mediaItemCount - 1)
                c.play()
            }
            else -> c.addMediaItem(item)
        }
        return true
    }

    /**
     * Queue edits from the UI. Each one names the [QueueSnapshot] the user was looking at and is
     * ignored if the playlist changed since, so a stale index never touches the wrong item. The
     * current item is never removed or moved, and nothing else can become current, so playback is
     * never interrupted, restarted or moved by these edits.
     */
    fun removeFromQueue(snapshot: QueueSnapshot, index: Int): Boolean {
        val c = editableController(snapshot) ?: return false
        if (index !in 0 until c.mediaItemCount || index == c.currentMediaItemIndex) return false
        c.removeMediaItem(index)
        return true
    }

    /** Moves an upcoming item to another upcoming position (after the current item). */
    fun moveInQueue(snapshot: QueueSnapshot, from: Int, to: Int): Boolean {
        val c = editableController(snapshot) ?: return false
        // With shuffle on, moving in the queue would not change what plays next: not offered.
        if (c.shuffleModeEnabled) return false
        val upcoming = c.currentMediaItemIndex + 1 until c.mediaItemCount
        if (from == to || from !in upcoming || to !in upcoming) return false
        c.moveMediaItem(from, to)
        return true
    }

    /** Removes every item after the current one; the current item keeps playing. */
    fun clearUpcoming(snapshot: QueueSnapshot): Boolean {
        val c = editableController(snapshot) ?: return false
        if (snapshot.shuffled) {
            // Upcoming in play order: everything after the current track in the shuffle order.
            val order = snapshot.playOrder
            val at = order.indexOf(c.currentMediaItemIndex)
            if (at < 0 || at + 1 >= order.size) return false
            removeIndices(c, order.copyOfRange(at + 1, order.size))
            return true
        }
        val first = c.currentMediaItemIndex + 1
        if (first >= c.mediaItemCount) return false
        c.removeMediaItems(first, c.mediaItemCount)
        return true
    }

    /** Removes the given queue indices, highest first, in as few contiguous ranges as possible. */
    private fun removeIndices(c: MediaController, indices: IntArray) {
        val sorted = indices.sortedArrayDescending()
        var i = 0
        while (i < sorted.size) {
            var low = sorted[i]
            val high = sorted[i]
            while (i + 1 < sorted.size && sorted[i + 1] == low - 1) { i++; low = sorted[i] }
            c.removeMediaItems(low, high + 1)
            i++
        }
    }

    /** Playback position of the current track, in ms (0 if nothing is loaded). */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    /** Duration of the current track in ms, or 0 while unknown. */
    fun durationMs(): Long = controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        clearMessages()
        c.seekTo(positionMs.coerceAtLeast(0))
    }

    /** The document being played (its URI travels in the request metadata), for lyrics and info. */
    fun currentDocumentUri(): android.net.Uri? = controller?.currentMediaItem?.requestMetadata?.mediaUri

    /** Format of the audio stream the player is actually playing, as reported by the player. */
    fun currentAudioFormat(): androidx.media3.common.Format? {
        val c = controller ?: return null
        val group = c.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        val track = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return null
        return group.getTrackFormat(track)
    }

    /** Turns shuffle on or off. The current track keeps playing at its position. */
    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Repeat off → repeat the whole queue → repeat the current track → off. */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = nextRepeatMode(c.repeatMode)
    }

    private fun editableController(snapshot: QueueSnapshot): MediaController? {
        val c = controller ?: return null
        if (snapshot !== cachedQueue || c.mediaItemCount != snapshot.size) return null
        return c
    }

    /**
     * Removes queued items that [filter] no longer allows: their folder was removed or lost
     * permission, or a rescan excluded them (e.g. `.nomedia`).
     */
    fun removeItemsNotAllowed(filter: QueueFilter) {
        val c = controller ?: return
        val startMs = SystemClock.elapsedRealtime()
        val before = c.mediaItemCount
        var end = before
        var removeCalls = 0
        // Walk backwards removing contiguous ranges, so indices stay valid and calls stay few.
        while (end > 0) {
            var start = end
            while (start > 0 && !c.getMediaItemAt(start - 1).isAllowedBy(filter)) start--
            if (start < end) {
                c.removeMediaItems(start, end)
                removeCalls++
            }
            end = start
            while (end > 0 && c.getMediaItemAt(end - 1).isAllowedBy(filter)) end--
        }
        Log.i(
            PERF_TAG,
            "queuePrune items=$before removed=${before - c.mediaItemCount} calls=$removeCalls " +
                "mainMs=${SystemClock.elapsedRealtime() - startMs}",
        )
    }

    private inline fun userSeek(action: (MediaController) -> Unit) {
        val c = controller ?: return
        clearMessages()
        action(c)
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
    }

    private fun clearMessages() {
        if (skippedCount == 0 && error == null) return
        skippedCount = 0
        lastSkippedTitle = null
        lastSkipped = null
        error = null
        publishState(rebuildQueue = false)
    }

    @OptIn(UnstableApi::class)
    private fun publishState(rebuildQueue: Boolean) {
        val c = controller ?: run {
            // Events received before onControllerReady: keep messages, wait for the controller.
            _state.update { it.copy(skippedCount = skippedCount, lastSkippedTitle = lastSkippedTitle, lastSkipped = lastSkipped, error = error) }
            return
        }
        val count = c.mediaItemCount
        if (rebuildQueue || cachedQueue.size != count) {
            cachedQueue = QueueSnapshot.of(c.currentTimeline, c.shuffleModeEnabled)
        }
        val hasCurrent = count > 0 && c.currentMediaItemIndex in 0 until count
        val metadata = c.mediaMetadata // includes tags read from the file once prepared
        // Tag title when present; the file-name title until the tags are read or if there is none.
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: c.currentMediaItem?.displayTitle()
        _state.value = PlaybackUiState(
            connection = ConnectionStatus.CONNECTED,
            queue = cachedQueue,
            currentIndex = if (hasCurrent) c.currentMediaItemIndex else -1,
            currentMediaId = if (hasCurrent) c.currentMediaItem?.mediaId else null,
            currentTitle = if (hasCurrent) title else null,
            currentArtist = if (hasCurrent) metadata.artist?.toString() else null,
            currentAlbum = if (hasCurrent) metadata.albumTitle?.toString() else null,
            showPlayButton = Util.shouldShowPlayButton(c),
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            skippedCount = skippedCount,
            lastSkippedTitle = lastSkippedTitle,
            lastSkipped = lastSkipped,
            error = error,
        )
    }

    internal companion object {
        /** Repeat off → repeat the whole queue → repeat the current track → off. */
        fun nextRepeatMode(mode: Int): Int = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        private const val TAG = "PlayerConnection"
        const val PERF_TAG = "NezhPerf"

        fun MediaItem.isAllowedBy(filter: QueueFilter): Boolean {
            val folder = requestMetadata.mediaUri?.let(FolderKey::of)
            return filter.allows(mediaId, folder)
        }
    }
}
