package com.hachicorp.nezh.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Reports the current track (media id = track id) whenever it changes, so the library can protect
 * its cached cover while it is in use and regenerate the cover if it had been evicted.
 * This is about the track, not the queue entry ([currentQueueEntry]): the same track again (queued
 * twice, repeated, or a new queue starting on it) keeps its cover in use and is not reported again.
 */
internal class CurrentTrackReporter(private val onTrackChanged: (String?) -> Unit) : Player.Listener {
    private var lastId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        if (id == lastId) return
        lastId = id
        onTrackChanged(id)
    }
}
