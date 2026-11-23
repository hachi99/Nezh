package com.hachicorp.nezh.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PlaybackErrorsTest {

    @Test
    fun corruptOrUnsupportedFilesAreFileErrors() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ).forEach { code ->
            assertEquals(PlaybackErrorKind.FILE, classifyPlaybackError(code, emptySequence()))
        }
    }

    @Test
    fun revokedSafGrantIsAPermissionError() {
        // What ExoPlayer actually reported on the emulator when the grant was revoked.
        val causes = sequenceOf(IOException("Unexpected SecurityException"), SecurityException("Permission Denial"))
        assertEquals(
            PlaybackErrorKind.PERMISSION,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, causes),
        )
    }

    @Test
    fun permissionWinsOverFileCode() {
        assertEquals(
            PlaybackErrorKind.PERMISSION,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, sequenceOf(SecurityException())),
        )
        assertEquals(
            PlaybackErrorKind.PERMISSION,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, emptySequence()),
        )
    }

    @Test
    fun deviceAndResourceErrorsAreNotBlamedOnTheFile() {
        listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        ).forEach { code ->
            assertEquals(PlaybackErrorKind.OTHER, classifyPlaybackError(code, emptySequence()))
        }
    }

    @Test
    fun skipsAreBoundedByQueueSize() {
        assertEquals(0, maxConsecutiveSkips(0))
        assertEquals(0, maxConsecutiveSkips(1))
        assertEquals(4, maxConsecutiveSkips(5))
    }
}
