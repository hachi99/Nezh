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

data class QueueItem(val id: String, val title: String)

/**
 * Read-only view of the queue for the UI. Backed by an immutable player [Timeline] snapshot, so a
 * playlist change costs O(1) instead of copying every item (6 full copies of a 10,000-item queue,
 * 29–81 ms each on the main thread, were measured per track tap). Items are created on demand,
 * for the rows actually shown. Equality is by snapshot: each playlist change is a new snapshot.
 */
class QueueSnapshot private constructor(
    private val timeline: Timeline?,
    private val items: List<QueueItem>?,
) {
    val size: Int = timeline?.windowCount ?: items!!.size

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size > 0

    operator fun get(index: Int): QueueItem {
        items?.let { return it[index] }
        val item = timeline!!.getWindow(index, Timeline.Window()).mediaItem
        return QueueItem(id = item.mediaId, title = item.displayTitle().orEmpty())
    }

    companion object {
        val EMPTY = QueueSnapshot(null, emptyList())
        internal fun of(timeline: Timeline) = QueueSnapshot(timeline, null)
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
    /** Files skipped because they could not be played, since the last user action. */
    val skippedCount: Int = 0,
    /** Title of the most recently skipped file, when [skippedCount] > 0. */
    val lastSkippedTitle: String? = null,
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
    private var error: PlaybackErrorReport? = null
    private var cachedQueue: QueueSnapshot = QueueSnapshot.EMPTY

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // A new queue snapshot is only needed when the playlist changed (B7).
            val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
            if (player.isPlaying) error = null
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
            if (sessionResult.resultCode == SessionResult.RESULT_SUCCESS && connected.playerError != null) {
                error = PlaybackErrorReport.fromBundle(sessionResult.extras)
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
        error = null
        publishState(rebuildQueue = false)
    }

    @OptIn(UnstableApi::class)
    private fun publishState(rebuildQueue: Boolean) {
        val c = controller ?: run {
            // Events received before onControllerReady: keep messages, wait for the controller.
            _state.update { it.copy(skippedCount = skippedCount, lastSkippedTitle = lastSkippedTitle, error = error) }
            return
        }
        val count = c.mediaItemCount
        if (rebuildQueue || cachedQueue.size != count) {
            cachedQueue = QueueSnapshot.of(c.currentTimeline)
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
            skippedCount = skippedCount,
            lastSkippedTitle = lastSkippedTitle,
            error = error,
        )
    }

    private companion object {
        const val TAG = "PlayerConnection"
        const val PERF_TAG = "NezhPerf"

        fun MediaItem.isAllowedBy(filter: QueueFilter): Boolean {
            val folder = requestMetadata.mediaUri?.let(FolderKey::of)
            return filter.allows(mediaId, folder)
        }
    }
}
