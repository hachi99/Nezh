package com.hachicorp.nezh.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.datasource.DataSourceBitmapLoader
import com.hachicorp.nezh.MainActivity
import com.hachicorp.nezh.NezhApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the [ExoPlayer] and its [MediaSession]. Playback lives here so it is independent from the
 * UI lifecycle; the in-memory queue is the player's playlist.
 *
 * The service is exported so the system UI, Bluetooth and other controllers can bind to it.
 * Controllers from other apps (identified by UID, which Binder provides and cannot be spoofed) only
 * get transport controls and read-only state; only Nezh itself can change the queue or settings.
 *
 * Threading: the player and the session live on a dedicated thread ([playbackThread]), not on the
 * main thread. Loading a queue of thousands of tracks (creating one media source per track and
 * publishing the queue to the platform session) took ~0.6 s of main-thread time on a Moto G7 Plus
 * in release, freezing the UI; on this thread it does not block the UI. Every access to the player
 * or the session, and every player and session callback, happens on that thread. The UI talks to
 * the session through a MediaController on the main thread, as before.
 */
class PlaybackService : MediaSessionService() {

    private val playbackThread = HandlerThread("NezhPlayback", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var playerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set once in [onCreate] (main thread), read by [onGetSession] on the main thread. */
    @Volatile
    private var mediaSession: MediaSession? = null

    /** Owns the "playback stopped by an error" state; the session only reads it. Player thread only. */
    private var errorRecovery: ErrorRecovery? = null

    @OptIn(UnstableApi::class) // setBitmapLoader
    override fun onCreate() {
        super.onCreate()
        playbackThread.start()
        playerHandler = Handler(playbackThread.looper)
        // The session must exist when onCreate returns (onGetSession follows right away), so the
        // main thread waits for it; this is the same work it used to do itself.
        mediaSession = onPlayerThread {
            val player = buildNezhPlayer(this, playbackThread.looper)
            player.addListener(ErrorRecovery(player).also { errorRecovery = it })
            player.addListener(CurrentTrackReporter { id -> (application as NezhApplication).library.onPlaybackTrackChanged(id) })
            MediaSession.Builder(this, player)
                .setCallback(SessionCallback())
                .setSessionActivity(createSessionActivity())
                .setBitmapLoader(createBitmapLoader())
                .build()
        }
    }

    /** Runs [block] on the player thread and waits for its result (main thread → player thread). */
    private fun <T> onPlayerThread(block: () -> T): T {
        if (Looper.myLooper() == playbackThread.looper) return block()
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        playerHandler.post {
            result.set(runCatching(block))
            done.countDown()
        }
        check(done.await(PLAYER_THREAD_TIMEOUT_S, TimeUnit.SECONDS)) { "The player thread did not respond" }
        return result.get().getOrThrow()
    }

    @OptIn(UnstableApi::class)
    private fun createBitmapLoader() = CachedArtworkBitmapLoader(DataSourceBitmapLoader.Builder(this).build())


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return
        playerHandler.post {
            val player = session.player
            val playbackOngoing = player.playWhenReady &&
                player.mediaItemCount > 0 &&
                player.playbackState != Player.STATE_ENDED
            if (!playbackOngoing) {
                player.pause()
                mainHandler.post { stopSelf() }
            }
        }
    }

    override fun onDestroy() {
        val session = mediaSession
        mediaSession = null
        // Orderly shutdown on the player's own thread: player, then session, then the thread.
        if (session != null) {
            try {
                onPlayerThread {
                    session.player.release()
                    session.release()
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not release the player and session", e)
            }
        }
        playbackThread.quitSafely()
        (application as NezhApplication).library.onPlaybackTrackChanged(null)
        super.onDestroy()
    }

    private fun createSessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Nezh's own UI. The media notification controller also runs in this process. */
    private fun isTrusted(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid()

    /** Sends an event to Nezh's own UI controllers only; never to external controllers. Player thread. */
    @OptIn(UnstableApi::class)
    private fun notifyNezhControllers(command: SessionCommand, args: Bundle) {
        val session = mediaSession ?: return
        session.connectedControllers
            .filter { isTrusted(it) && !session.isMediaNotificationController(it) }
            .forEach { session.sendCustomCommand(it, command, args) }
    }

    /** Called by Media3 on the player thread. */
    private inner class SessionCallback : MediaSession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (isTrusted(controller)) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(GET_ERROR_COMMAND)
                            .build()
                    )
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(EXTERNAL_PLAYER_COMMANDS)
                .setAvailableSessionCommands(SessionCommands.EMPTY)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_GET_ERROR && isTrusted(controller)) {
                val extras = errorRecovery?.stoppedBy?.toBundle() ?: Bundle.EMPTY
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        /** Defense in depth: external controllers do not have the commands that reach this. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (!isTrusted(controller)) {
                Log.w(TAG, "Rejected media items from an external controller")
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("Adding media items is not allowed for this controller")
                )
            }
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: return Futures.immediateFailedFuture(
                        IllegalArgumentException("Media item has no URI")
                    )
                item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    /** Why playback is stopped, as owned by [ErrorRecovery]. */
    private sealed interface Stop {
        val report: PlaybackErrorReport

        data class PlayerError(override val report: PlaybackErrorReport) : Stop
        data class Inaudible(override val report: PlaybackErrorReport) : Stop
    }

    /**
     * Skips past files that cannot be played; stops on any other error.
     *
     * Only [PlaybackErrorKind.FILE] errors skip. Permission, device and resource errors are not
     * the file's fault: skipping would fail the same way and mislabel valid files, so playback
     * stops and the UI is told why. Consecutive skips are bounded by the queue size.
     */
    private inner class ErrorRecovery(private val player: Player) : Player.Listener {
        private var consecutiveSkips = 0
        /** Index we moved to by skipping; that transition must not reset the counter. */
        private var skipTargetIndex = C.INDEX_UNSET

        /**
         * Why playback stopped, while it is stopped by a failure; null otherwise. One value, so a
         * report and the way it ends cannot disagree:
         * - [Stop.PlayerError]: the player is in an error state; ends when it leaves it (a retry).
         * - [Stop.Inaudible]: a file without decodable audio; the player is only paused, so it ends
         *   when audio plays or another item becomes current.
         */
        private var stop: Stop? = null

        /** The report a reconnecting Nezh UI is given. */
        val stoppedBy: PlaybackErrorReport? get() = stop?.report

        override fun onPlayerError(error: PlaybackException) {
            val kind = error.kind()
            Log.w(
                TAG,
                "Playback error at item ${player.currentMediaItemIndex}: ${error.errorCodeName}, " +
                    "kind=$kind, cause=${rootCauseName(error)}",
            )
            onFailure(report(kind, stageOf(error.errorCode), error.errorCodeName))
        }

        /**
         * ExoPlayer raises no error when no decoder on the device can play a file's audio (e.g.
         * ALAC where the device has no ALAC decoder): it leaves the track unselected and "plays"
         * silence on its own clock. Treated as an unsupported format, like a decoder error.
         */
        override fun onTracksChanged(tracks: Tracks) {
            if (player.playbackState != Player.STATE_IDLE) checkAudible(tracks)
        }

        /** Reports and skips or pauses when [tracks] cannot produce sound. False if it did. */
        private fun checkAudible(tracks: Tracks): Boolean {
            val reason = inaudibleReason(tracks) ?: return true
            Log.w(TAG, "No playable audio at item ${player.currentMediaItemIndex}: $reason")
            val code = "${PlaybackException.getErrorCodeName(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)}: $reason"
            onFailure(
                report(PlaybackErrorKind.FILE, PlaybackErrorStage.DECODER, code),
                inaudible = true,
            )
            return false
        }

        private fun onFailure(report: PlaybackErrorReport, inaudible: Boolean = false) {
            // Skip only while the user wants playback: when paused or stopped, preparing the next
            // file would keep the chain going (measured: pausing did not stop it on a queue of
            // broken files), so the error stays on the current file and is reported instead.
            val canSkip = report.kind == PlaybackErrorKind.FILE &&
                player.playWhenReady &&
                consecutiveSkips < maxConsecutiveSkips(player.mediaItemCount) &&
                player.hasNextMediaItem()
            if (canSkip) {
                consecutiveSkips++
                notifyNezhControllers(ITEM_SKIPPED_COMMAND, report.toBundle())
                // Set before seeking, in case the transition is reported synchronously.
                skipTargetIndex = player.nextMediaItemIndex
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                // An inaudible file leaves the player READY: pause it so it does not run silently.
                if (inaudible) player.pause()
                stop = if (inaudible) Stop.Inaudible(report) else Stop.PlayerError(report)
                notifyNezhControllers(PLAYBACK_STOPPED_COMMAND, report.toBundle())
            }
        }

        /** A report about the current item, which it names and identifies. */
        private fun report(kind: PlaybackErrorKind, stage: PlaybackErrorStage, code: String): PlaybackErrorReport {
            val item = player.currentMediaItem
            return PlaybackErrorReport(kind, item?.displayTitle().orEmpty(), stage, code, item?.mediaId.orEmpty())
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null && stop is Stop.PlayerError) stop = null
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            val tracks = player.currentTracks
            if (tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                consecutiveSkips = 0
                clearInaudibleStop()
            } else if (player.playWhenReady) {
                // Play pressed again on a file that cannot sound (its tracks did not change).
                checkAudible(tracks)
            }
        }

        private fun clearInaudibleStop() {
            if (stop is Stop.Inaudible) stop = null
        }

        /** The queue entry last seen as current ([currentQueueEntry]). */
        private var currentEntry: Any? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val entry = player.currentQueueEntry()
            // A playlist change that keeps the same queue entry only re-describes it: not a move.
            // A new queue is a new entry even when it starts on the same track (same media id).
            val sameEntry = entry == currentEntry && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            currentEntry = entry
            if (sameEntry) return
            clearInaudibleStop()
            if (player.currentMediaItemIndex == skipTargetIndex) {
                skipTargetIndex = C.INDEX_UNSET
            } else {
                // The user (or playback) moved elsewhere: a new run of errors starts from zero.
                consecutiveSkips = 0
                skipTargetIndex = C.INDEX_UNSET
            }
        }

        private fun rootCauseName(error: Throwable): String =
            generateSequence(error) { it.cause }.take(16).last().javaClass.simpleName
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** Generous: building or releasing the player takes milliseconds. */
        private const val PLAYER_THREAD_TIMEOUT_S = 10L

        internal const val ACTION_ITEM_SKIPPED = "com.hachicorp.nezh.ITEM_SKIPPED"
        internal const val ACTION_PLAYBACK_STOPPED = "com.hachicorp.nezh.PLAYBACK_STOPPED"
        internal const val ACTION_GET_ERROR = "com.hachicorp.nezh.GET_ERROR"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_KIND = "kind"
        internal const val EXTRA_STAGE = "stage"
        internal const val EXTRA_CODE = "code"
        internal const val EXTRA_MEDIA_ID = "mediaId"

        /** Session → Nezh UI: a file failed and was skipped. */
        internal val ITEM_SKIPPED_COMMAND = SessionCommand(ACTION_ITEM_SKIPPED, Bundle.EMPTY)
        /** Session → Nezh UI: playback stopped because of an error. */
        internal val PLAYBACK_STOPPED_COMMAND = SessionCommand(ACTION_PLAYBACK_STOPPED, Bundle.EMPTY)
        /** Nezh UI → session: returns the error that stopped playback, if any. */
        internal val GET_ERROR_COMMAND = SessionCommand(ACTION_GET_ERROR, Bundle.EMPTY)

        /**
         * What external controllers (system UI, lock screen, Bluetooth, cars, other apps) may do:
         * transport controls plus the read-only state needed to display what is playing.
         */
        private val EXTERNAL_PLAYER_COMMANDS: Player.Commands = externalPlayerCommands()

        @OptIn(UnstableApi::class)
        private fun externalPlayerCommands(): Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            )
            .build()
    }
}

/** A classified error, as sent to Nezh's UI. */
data class PlaybackErrorReport(
    val kind: PlaybackErrorKind,
    val title: String,
    /** Where it failed: reading the file, the container, the decoder... */
    val stage: PlaybackErrorStage = PlaybackErrorStage.OTHER,
    /** Media3 error code name, e.g. ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (diagnostics). */
    val code: String = "",
    /** Queue item the error is about; the UI drops the message once another item is current. */
    val mediaId: String = "",
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(PlaybackService.EXTRA_KIND, kind.name)
        putString(PlaybackService.EXTRA_TITLE, title)
        putString(PlaybackService.EXTRA_STAGE, stage.name)
        putString(PlaybackService.EXTRA_CODE, code)
        putString(PlaybackService.EXTRA_MEDIA_ID, mediaId)
    }

    companion object {
        fun fromBundle(bundle: Bundle): PlaybackErrorReport? {
            val kindName = bundle.getString(PlaybackService.EXTRA_KIND) ?: return null
            val kind = PlaybackErrorKind.entries.firstOrNull { it.name == kindName } ?: return null
            val stage = PlaybackErrorStage.entries.firstOrNull { it.name == bundle.getString(PlaybackService.EXTRA_STAGE) }
                ?: PlaybackErrorStage.OTHER
            return PlaybackErrorReport(
                kind,
                bundle.getString(PlaybackService.EXTRA_TITLE).orEmpty(),
                stage,
                bundle.getString(PlaybackService.EXTRA_CODE).orEmpty(),
                bundle.getString(PlaybackService.EXTRA_MEDIA_ID).orEmpty(),
            )
        }
    }
}
