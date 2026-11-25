package com.hachicorp.nezh.library

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Lyrics stored inside the audio file itself, as written by taggers. Only local metadata: no
 * network, no lookup, no sidecar files. Supported containers:
 * - MP3 (ID3v2.3/2.4 USLT frame),
 * - FLAC (Vorbis comment LYRICS or UNSYNCEDLYRICS),
 * - MP4/M4A (iTunes "©lyr" atom).
 * Only the tag/metadata area is read, never the audio. The text is returned as stored (line breaks
 * normalised to \n), never altered.
 */
internal object EmbeddedLyrics {

    /** Largest metadata block read (FLAC comment block, MP4 moov box). */
    private const val MAX_BLOCK = 32 * 1024 * 1024

    /** Lyrics in [input] (the start of the file), or null if the file has none. */
    fun read(input: InputStream): String? {
        val data = DataInputStream(input.buffered())
        data.mark(12)
        val head = ByteArray(12)
        val n = data.read(head)
        data.reset()
        if (n < 8) return null
        val text = when {
            head[0] == 'I'.b && head[1] == 'D'.b && head[2] == '3'.b -> Id3Tag.read(data, wantPicture = false)?.lyrics
            head[0] == 'f'.b && head[1] == 'L'.b && head[2] == 'a'.b && head[3] == 'C'.b -> flac(data)
            head[4] == 'f'.b && head[5] == 't'.b && head[6] == 'y'.b && head[7] == 'p'.b -> mp4(data)
            else -> null
        }
        return text?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim('\n', ' ', '\u0000')?.takeIf { it.isNotBlank() }
    }

    // --- FLAC: metadata blocks, VORBIS_COMMENT (type 4) ------------------------------------------

    private fun flac(data: DataInputStream): String? {
        data.skipFully(4) // "fLaC"
        while (true) {
            val header = data.readUnsignedByte()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            if (type == 4) {
                if (length > MAX_BLOCK) return null
                val block = ByteArray(length).also { data.readFully(it) }
                return vorbisLyrics(block)
            }
            data.skipFully(length.toLong())
            if (last) return null
        }
    }

    /** Vorbis comments: little-endian lengths; keys are case-insensitive. */
    internal fun vorbisLyrics(block: ByteArray): String? {
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 4) return null
        val vendor = b.int
        if (vendor < 0 || vendor > b.remaining()) return null
        b.position(b.position() + vendor)
        if (b.remaining() < 4) return null
        val count = b.int
        var unsynced: String? = null
        repeat(count) {
            if (b.remaining() < 4) return unsynced
            val len = b.int
            if (len < 0 || len > b.remaining()) return unsynced
            val bytes = ByteArray(len).also { b.get(it) }
            val comment = utf8(bytes) ?: return@repeat
            val eq = comment.indexOf('=')
            if (eq <= 0) return@repeat
            when (comment.substring(0, eq).uppercase()) {
                "LYRICS" -> return comment.substring(eq + 1)
                "UNSYNCEDLYRICS" -> if (unsynced == null) unsynced = comment.substring(eq + 1)
            }
        }
        return unsynced
    }

    // --- MP4: moov/udta/meta/ilst/©lyr/data -------------------------------------------------------

    private fun mp4(data: DataInputStream): String? {
        while (true) {
            val (type, bodySize) = boxHeader(data) ?: return null
            if (type == "moov") {
                if (bodySize !in 0..MAX_BLOCK) return null
                val moov = ByteArray(bodySize.toInt()).also { data.readFully(it) }
                return mp4Lyrics(moov)
            }
            if (bodySize < 0) return null // box runs to the end of the file: no moov after it
            data.skipFully(bodySize)
        }
    }

    /** Finds ©lyr inside a moov box body. */
    internal fun mp4Lyrics(moov: ByteArray): String? {
        val udta = child(moov, 0, moov.size, "udta") ?: return null
        val meta = child(moov, udta.first, udta.end, "meta") ?: return null
        // "meta" is a full box (4 bytes version/flags) in iTunes files, a plain box in QuickTime ones.
        val metaStart = if (meta.end - meta.first >= 8 && String(moov, meta.first + 4, 4, Charsets.ISO_8859_1) == "hdlr") meta.first else meta.first + 4
        val ilst = child(moov, metaStart, meta.end, "ilst") ?: return null
        val lyr = child(moov, ilst.first, ilst.end, "©lyr") ?: return null
        val dataBox = child(moov, lyr.first, lyr.end, "data") ?: return null
        val textStart = dataBox.first + 8 // type indicator + locale
        if (textStart > dataBox.end) return null
        return utf8(moov.copyOfRange(textStart, dataBox.end))
    }

    /** Body range [first, last) of the first child box of [type] within [from, to). */
    private fun child(b: ByteArray, from: Int, to: Int, type: String): IntRange? {
        var pos = from
        while (pos + 8 <= to) {
            var size = int32(b, pos).toLong() and 0xFFFFFFFFL
            val name = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && pos + 16 <= to) {
                size = ByteBuffer.wrap(b, pos + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < header || pos + size > to) return null
            if (name == type) return (pos + header) until (pos + size).toInt()
            pos += size.toInt()
        }
        return null
    }

    /** Exclusive end of a body range. */
    private val IntRange.end get() = last + 1

    /** Reads a box header; returns its type and body size (-1 if it runs to the end of the file). */
    private fun boxHeader(data: DataInputStream): Pair<String, Long>? = try {
        val size = data.readInt().toLong() and 0xFFFFFFFFL
        val type = ByteArray(4).also { data.readFully(it) }.toString(Charsets.ISO_8859_1)
        when (size) {
            1L -> data.readLong().takeIf { it >= 16 }?.let { type to (it - 16) } // 64-bit size
            0L -> type to -1L // runs to the end of the file
            in 2L..7L -> null // smaller than its own header: malformed
            else -> type to (size - 8)
        }
    } catch (e: EOFException) {
        null
    }

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private val Char.b get() = code.toByte()
}

/** Reads embedded lyrics of a document through the ContentResolver. Blocking: background thread. */
class LyricsReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /** The lyrics, or null if the file has none or cannot be read (never throws for file problems). */
    fun read(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { EmbeddedLyrics.read(it) }
    } catch (e: IOException) {
        Log.i(TAG, "Lyrics not readable: ${e.javaClass.simpleName}")
        null
    } catch (e: SecurityException) {
        Log.i(TAG, "Lyrics not readable: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "LyricsReader"
    }
}
