package com.hachicorp.nezh.library

/** MIME type SAF providers use for directories (`DocumentsContract.Document.MIME_TYPE_DIR`). */
internal const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

private val PLAYLIST_MIME_TYPES = setOf(
    "audio/x-mpegurl",
    "audio/mpegurl",
    "audio/x-scpls",
    "application/vnd.apple.mpegurl",
)
private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")

/** Used only when the provider reports no MIME type or a generic one. */
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka", "amr",
)

/**
 * Whether a document is an audio track Nezh should list. Hidden files (dot-prefixed, e.g. Android's
 * `.trashed-*` files) and playlist files are excluded.
 */
internal fun isAudioDocument(mimeType: String?, displayName: String): Boolean {
    if (displayName.startsWith('.')) return false
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension in PLAYLIST_EXTENSIONS) return false
    val mime = mimeType?.lowercase()
    if (mime in PLAYLIST_MIME_TYPES) return false
    return when {
        mime == null || mime == "application/octet-stream" -> extension in AUDIO_EXTENSIONS
        mime.startsWith("audio/") -> true
        mime == "application/ogg" || mime == "application/x-flac" -> true
        else -> false
    }
}

/** "Song name.mp3" -> "Song name". Names without an extension (or dot-files) are kept as-is. */
internal fun titleFromDisplayName(displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    return if (dot > 0) displayName.substring(0, dot) else displayName
}
