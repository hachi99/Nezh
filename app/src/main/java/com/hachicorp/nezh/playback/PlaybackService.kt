package com.hachicorp.nezh.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

/**
 * Owns the [ExoPlayer] and its [MediaSession]. Playback lives here so it is independent from the
 * UI lifecycle; the in-memory queue is the player's playlist.
 *
 * The service is exported so the system UI, Bluetooth and other controllers can bind to it.
 * Controllers from other apps (identified by UID, which Binder provides and cannot be spoofed) only
 * get transport controls and read-only state; only Nezh itself can change the queue or settings.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** The classified error that stopped playback, if the player is currently in an error state. */
    private var stoppedBy: PlaybackErrorReport? = null

    @OptIn(UnstableApi::class) // setBitmapLoader
    override fun onCreate() {
        super.onCreate()
        val player = buildNezhPlayer(this)
        player.addListener(ErrorRecovery(player))

        player.addListener(CurrentTrackReporter { id -> (application as NezhApplication).library.onPlaybackTrackChanged(id) })

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(createSessionActivity())
            .setBitmapLoader(createBitmapLoader())
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun createBitmapLoader() = CachedArtworkBitmapLoader(DataSourceBitmapLoader.Builder(this).build())


    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val playbackOngoing = player != null &&
            player.playWhenReady &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED
        if (!playbackOngoing) {
            player?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        (application as NezhApplication).library.onPlaybackTrackChanged(null)
        mediaSession = null
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

    /** Sends an event to Nezh's own UI controllers only; never to external controllers. */
    @OptIn(UnstableApi::class)
    private fun notifyNezhControllers(command: SessionCommand, args: Bundle) {
        val session = mediaSession ?: return
        session.connectedControllers
            .filter { isTrusted(it) && !session.isMediaNotificationController(it) }
            .forEach { session.sendCustomCommand(it, command, args) }
    }

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
                val extras = stoppedBy?.toBundle() ?: Bundle.EMPTY
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

        override fun onPlayerError(error: PlaybackException) {
            val kind = error.kind()
            val title = player.currentMediaItem?.displayTitle().orEmpty()
            Log.w(
                TAG,
                "Playback error at item ${player.currentMediaItemIndex}: ${error.errorCodeName}, " +
                    "kind=$kind, cause=${rootCauseName(error)}",
            )
            // Skip only while the user wants playback: when paused or stopped, preparing the next
            // file would keep the chain going (measured: pausing did not stop it on a queue of
            // broken files), so the error stays on the current file and is reported instead.
            val canSkip = kind == PlaybackErrorKind.FILE &&
                player.playWhenReady &&
                consecutiveSkips < maxConsecutiveSkips(player.mediaItemCount) &&
                player.hasNextMediaItem()
            if (canSkip) {
                consecutiveSkips++
                notifyNezhControllers(ITEM_SKIPPED_COMMAND, Bundle().apply { putString(EXTRA_TITLE, title) })
                // Set before seeking, in case the transition is reported synchronously.
                skipTargetIndex = player.nextMediaItemIndex
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                val report = PlaybackErrorReport(kind, title)
                stoppedBy = report
                notifyNezhControllers(PLAYBACK_STOPPED_COMMAND, report.toBundle())
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null) stoppedBy = null
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveSkips = 0
        }

        /** Id of the current item, to ignore metadata-only updates of the same item. */
        private var currentMediaId: String? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val sameItem = mediaItem?.mediaId == currentMediaId &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            currentMediaId = mediaItem?.mediaId
            // TitleFallback re-describes the current item; that is not a new item and must not
            // reset the counter (otherwise a queue of broken files could loop).
            if (sameItem) return
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

        internal const val ACTION_ITEM_SKIPPED = "com.hachicorp.nezh.ITEM_SKIPPED"
        internal const val ACTION_PLAYBACK_STOPPED = "com.hachicorp.nezh.PLAYBACK_STOPPED"
        internal const val ACTION_GET_ERROR = "com.hachicorp.nezh.GET_ERROR"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_KIND = "kind"

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
data class PlaybackErrorReport(val kind: PlaybackErrorKind, val title: String) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(PlaybackService.EXTRA_KIND, kind.name)
        putString(PlaybackService.EXTRA_TITLE, title)
    }

    companion object {
        fun fromBundle(bundle: Bundle): PlaybackErrorReport? {
            val kindName = bundle.getString(PlaybackService.EXTRA_KIND) ?: return null
            val kind = PlaybackErrorKind.entries.firstOrNull { it.name == kindName } ?: return null
            return PlaybackErrorReport(kind, bundle.getString(PlaybackService.EXTRA_TITLE).orEmpty())
        }
    }
}
