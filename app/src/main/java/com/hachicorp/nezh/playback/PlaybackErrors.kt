package com.hachicorp.nezh.playback

import androidx.media3.common.PlaybackException

/** What a playback error means for Nezh. */
enum class PlaybackErrorKind {
    /** The file itself cannot be played (missing, corrupt, unsupported). Safe to skip. */
    FILE,
    /** Nezh lost access to the file's folder. Skipping would fail the same way for the folder. */
    PERMISSION,
    /** Device, decoder resources, audio output or anything not attributable to the file. */
    OTHER,
}

private val FILE_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
)

/**
 * Classifies an error from its code and cause chain. Permission problems are detected first:
 * ExoPlayer reports a revoked SAF grant as ERROR_CODE_IO_UNSPECIFIED caused by SecurityException.
 */
internal fun classifyPlaybackError(errorCode: Int, causes: Sequence<Throwable>): PlaybackErrorKind = when {
    errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackErrorKind.PERMISSION
    causes.any { it is SecurityException } -> PlaybackErrorKind.PERMISSION
    errorCode in FILE_ERROR_CODES -> PlaybackErrorKind.FILE
    else -> PlaybackErrorKind.OTHER
}

internal fun PlaybackException.kind(): PlaybackErrorKind =
    classifyPlaybackError(errorCode, generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH))

private const val MAX_CAUSE_DEPTH = 16

/**
 * Maximum number of consecutive automatic skips for a queue of [queueSize] items: every other item
 * can be tried once, so a queue of broken files stops instead of looping (even with repeat on).
 */
internal fun maxConsecutiveSkips(queueSize: Int): Int = (queueSize - 1).coerceAtLeast(0)
