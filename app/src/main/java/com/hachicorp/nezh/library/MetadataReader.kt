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
 *
 * For MP3 files, the ID3 tag is also read with [Id3Tag] when the platform result may be wrong:
 * some field missing, or containing non-ASCII text. Measured platform failures (Unicode fixtures):
 * text declared UTF-16 re-detected as another character set (every field garbled), and tags over
 * ~3 MB ignored entirely (no title, artist, album or cover). Per field:
 * - declared Unicode (UTF-16 / UTF-8): Nezh's exact reading wins;
 * - declared ISO-8859-1: the platform reading is kept (its detection correctly reads legacy
 *   encodings such as Shift-JIS stored as "ISO-8859-1"), or Nezh's if the platform had none;
 * - not in the tag: the platform reading is kept.
 * The text is never altered, replaced or transliterated.
 */
class PlatformMetadataReader(context: Context) : MetadataReader {
    private val appContext = context.applicationContext

    override fun read(uri: Uri): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val platform = AudioMetadata(
                title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                format = retriever.text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                picture = retriever.embeddedPicture,
            )
            if (needsId3Check(platform)) withId3Tag(uri, platform) else platform
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

    private fun needsId3Check(m: AudioMetadata): Boolean =
        m.format == "audio/mpeg" &&
            listOf(m.title, m.artist, m.album).any { it == null || it.any { c -> c.code > 0x7F } }

    private fun withId3Tag(uri: Uri, platform: AudioMetadata): AudioMetadata {
        val tag = try {
            Id3Tag.readOrNull({ appContext.contentResolver.openInputStream(uri) }, wantPicture = platform.picture == null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ID3 tag not readable, platform metadata kept: ${e.javaClass.simpleName}")
            null
        } ?: return platform
        return AudioMetadata(
            title = merge(platform.title, tag.title),
            artist = merge(platform.artist, tag.artist ?: tag.albumArtist),
            album = merge(platform.album, tag.album),
            durationMs = platform.durationMs,
            format = platform.format,
            picture = platform.picture ?: tag.picture,
        )
    }

    private fun merge(platform: String?, own: Id3Tag.TextField?): String? = when {
        own == null -> platform
        own.declaredUnicode -> own.value
        else -> platform ?: own.value
    }

    private fun MediaMetadataRetriever.text(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "MetadataReader"
    }
}
