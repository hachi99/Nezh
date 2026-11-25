package com.hachicorp.nezh.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks

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

/** Where in the pipeline a playback error happened. Shown to the user with the Media3 code. */
enum class PlaybackErrorStage {
    /** Opening or reading the document (missing, unreadable, permission). */
    READ_FILE,
    /** Container/extractor: unsupported or malformed file structure. */
    CONTAINER,
    /** Decoder: the audio format cannot be decoded on this device, or decoding failed. */
    DECODER,
    OTHER,
}

/** Media3 error codes are grouped: 2xxx I/O, 3xxx parsing, 4xxx decoding. */
internal fun stageOf(errorCode: Int): PlaybackErrorStage = when (errorCode) {
    in 2000..2999 -> PlaybackErrorStage.READ_FILE
    in 3000..3999 -> PlaybackErrorStage.CONTAINER
    in 4000..4999 -> PlaybackErrorStage.DECODER
    else -> PlaybackErrorStage.OTHER
}

internal fun PlaybackException.kind(): PlaybackErrorKind =
    classifyPlaybackError(errorCode, generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH))

private const val MAX_CAUSE_DEPTH = 16

/**
 * Maximum number of consecutive automatic skips for a queue of [queueSize] items: every other item
 * can be tried once, so a queue of broken files stops instead of looping (even with repeat on).
 */
internal fun maxConsecutiveSkips(queueSize: Int): Int = (queueSize - 1).coerceAtLeast(0)

/**
 * Why the current file would play without sound, or null when an audio track is selected or the
 * tracks are not known yet. ExoPlayer selects no audio track when no decoder supports the format
 * and then advances silently instead of failing. Returns the audio MIME types found (for example
 * "audio/alac"), or "no audio track".
 */
internal fun inaudibleReason(tracks: Tracks): String? = inaudibleReason(
    tracksKnown = !tracks.isEmpty,
    audioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
    audioFormats = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).sampleMimeType ?: "unknown audio format" } },
)

/** [audioFormats]: sample MIME type of every audio track in the file (empty: no audio track). */
internal fun inaudibleReason(tracksKnown: Boolean, audioSelected: Boolean, audioFormats: List<String>): String? = when {
    !tracksKnown || audioSelected -> null
    audioFormats.isEmpty() -> "no audio track"
    else -> audioFormats.distinct().joinToString()
}
