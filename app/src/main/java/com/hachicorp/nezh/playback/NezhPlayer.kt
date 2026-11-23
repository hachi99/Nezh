package com.hachicorp.nezh.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.hachicorp.nezh.library.Track

/** MediaMetadata extra holding the title derived from the file name (fallback only). */
internal const val EXTRA_FILE_TITLE = "com.hachicorp.nezh.FILE_TITLE"

/**
 * Builds the player used by [PlaybackService]. Shared with the instrumented format tests so they
 * exercise exactly the production configuration.
 *
 * Constant-bitrate seeking is enabled for streams with no seek information (raw ADTS AAC, AMR):
 * without it those files are not seekable at all (measured: ADTS `.aac` reported seekable=false).
 * MP3 already uses constant-bitrate seeking by default when it has no Xing/VBRI header.
 */
@OptIn(UnstableApi::class)
internal fun buildNezhPlayer(context: Context): ExoPlayer {
    val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    player.addListener(TitleFallback(player))
    return player
}

/**
 * Queue item for a library track. The URI travels in request metadata (see PlaybackService).
 *
 * The file-name title is stored as an extra, not as the title: ExoPlayer gives MediaItem metadata
 * priority over the file's own tags, so setting it as the title hid every tagged title (measured).
 * [TitleFallback] applies it only when the file has no title tag. The title tag read by the index,
 * when known, is set directly (it is the file's own tag).
 */
internal fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            // Only the title read from the file's own tags (from the index); never the file name.
            .setTitle(tagTitle)
            // Cached cover (≤ 512 px) from the index; the session prefers it to the embedded image.
            .setArtworkUri(artworkUri?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply { putString(EXTRA_FILE_TITLE, fileTitle) })
            .build()
    )
    .build()

internal fun MediaItem.fileTitle(): String? = mediaMetadata.extras?.getString(EXTRA_FILE_TITLE)

/** Title to show for a queue item: its title if known, else the file-name title. */
internal fun MediaItem.displayTitle(): String? =
    mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: fileTitle()

/**
 * Once the current file's tags are known, gives it the file-name title if it has no title tag, so
 * the notification, lock screen and Bluetooth (which only read the title) still show something.
 * Replacing an item with the same URI only updates its metadata; playback is not interrupted.
 */
private class TitleFallback(private val player: Player) : Player.Listener {
    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.isEmpty) return
        if (!player.mediaMetadata.title.isNullOrBlank()) return
        val item = player.currentMediaItem ?: return
        val fallback = item.fileTitle() ?: return
        val updated = item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(fallback).build())
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }
}
