package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFilesTest {
    @Test
    fun audioMimeTypesAreAccepted() {
        assertTrue(isAudioDocument("audio/mpeg", "song.mp3"))
        assertTrue(isAudioDocument("audio/flac", "song.flac"))
        assertTrue(isAudioDocument("application/ogg", "song.ogg"))
    }

    @Test
    fun nonAudioIsRejected() {
        assertFalse(isAudioDocument("image/jpeg", "cover.jpg"))
        assertFalse(isAudioDocument("video/mp4", "clip.mp4"))
        assertFalse(isAudioDocument("text/plain", "notes.mp3"))
    }

    @Test
    fun extensionIsUsedOnlyWhenMimeTypeIsMissingOrGeneric() {
        assertTrue(isAudioDocument(null, "song.opus"))
        assertTrue(isAudioDocument("application/octet-stream", "song.FLAC"))
        assertFalse(isAudioDocument(null, "document.pdf"))
    }

    @Test
    fun playlistsAreNotTracks() {
        assertFalse(isAudioDocument("audio/x-mpegurl", "list.m3u"))
        assertFalse(isAudioDocument("audio/mpeg", "list.m3u8"))
    }

    @Test
    fun hiddenFilesAreIgnored() {
        assertFalse(isAudioDocument("audio/mpeg", ".trashed-1700000000-song.mp3"))
    }

    @Test
    fun titleFromDisplayName_stripsOnlyLastExtension() {
        assertEquals("Song name", titleFromDisplayName("Song name.mp3"))
        assertEquals("Artist - Song.live", titleFromDisplayName("Artist - Song.live.flac"))
        assertEquals("Song", titleFromDisplayName("Song"))
        assertEquals(".hidden", titleFromDisplayName(".hidden"))
    }
}
