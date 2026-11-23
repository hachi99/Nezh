package com.hachicorp.nezh.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Tags and properties read from an audio file. Every field is optional. */
class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    /** Container MIME type detected from the file. */
    val format: String?,
    /** Embedded picture bytes, as stored in the file. */
    val picture: ByteArray?,
)

/** Reads metadata of one document. Blocking: call from a background thread. */
fun interface MetadataReader {
    /** Returns null when the file cannot be read; the track is kept with its file-name title. */
    fun read(uri: Uri): AudioMetadata?
}

/**
 * [MetadataReader] backed by the platform MediaMetadataRetriever, which opens the document through
 * the ContentResolver (so the SAF permission applies) and does not read the whole file.
 * Logs never include the URI.
 */
class PlatformMetadataReader(context: Context) : MetadataReader {
    private val appContext = context.applicationContext

    override fun read(uri: Uri): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            AudioMetadata(
                title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                format = retriever.text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                picture = retriever.embeddedPicture,
            )
        } catch (e: RuntimeException) {
            // IllegalArgumentException / RuntimeException for unreadable or unsupported files,
            // SecurityException when access was lost.
            Log.i(TAG, "Cannot read metadata: ${e.javaClass.simpleName}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Retriever release failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun MediaMetadataRetriever.text(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "MetadataReader"
    }
}
