package com.hachicorp.nezh.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Reports the current track (media id = track id) whenever it changes, so the library can protect
 * its cached cover while it is in use and regenerate the cover if it had been evicted.
 * Metadata-only updates of the same item (e.g. TitleFallback) are not reported again.
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
