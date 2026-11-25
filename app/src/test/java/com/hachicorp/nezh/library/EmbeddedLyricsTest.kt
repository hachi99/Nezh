package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbeddedLyricsTest {

    private val japanese = "君の声が\n聞こえる\r\n夜明けまで"
    private val expected = "君の声が\n聞こえる\n夜明けまで"
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(300)

    private fun read(bytes: ByteArray) = EmbeddedLyrics.read(bytes.inputStream())

    // --- MP3 / ID3 -------------------------------------------------------------------------------

    private fun synchsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun int32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun id3(version: Int, vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, (id, b) ->
            acc + id.toByteArray() + (if (version == 4) synchsafe(b.size) else int32(b.size)) + byteArrayOf(0, 0) + b
        }
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) + synchsafe(body.size) + body
    }

    private fun utf16(s: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    @Test
    fun mp3UnsynchronisedLyricsInUtf16AndUtf8() {
        val uslt16 = byteArrayOf(1) + "jpn".toByteArray() + utf16("歌詞") + byteArrayOf(0, 0) + utf16(japanese)
        assertEquals(expected, read(id3(3, "TIT2" to (byteArrayOf(1) + utf16("Title")), "USLT" to uslt16) + audio))
        val uslt8 = byteArrayOf(3) + "jpn".toByteArray() + byteArrayOf(0) + japanese.toByteArray()
        assertEquals(expected, read(id3(4, "USLT" to uslt8) + audio))
    }

    @Test
    fun mp3WithoutLyricsGivesNull() {
        assertNull(read(id3(3, "TIT2" to (byteArrayOf(1) + utf16("Title"))) + audio))
        assertNull(read(audio))
    }

    // --- FLAC ------------------------------------------------------------------------------------

    private fun vorbis(vararg comments: String): ByteArray {
        val vendor = "nezh".toByteArray()
        val out = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(vendor.size).put(vendor).putInt(comments.size)
        comments.forEach { c -> val b = c.toByteArray(); out.putInt(b.size).put(b) }
        return out.array().copyOf(out.position())
    }

    private fun flac(vararg blocks: Pair<Int, ByteArray>): ByteArray {
        var out = "fLaC".toByteArray()
        blocks.forEachIndexed { i, (type, data) ->
            val header = (if (i == blocks.lastIndex) 0x80 else 0) or type
            out += byteArrayOf(header.toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data
        }
        return out + ByteArray(64)
    }

    @Test
    fun flacLyricsCommentIsReadWhateverTheKeyCase() {
        val streamInfo = ByteArray(34)
        assertEquals(expected, read(flac(0 to streamInfo, 4 to vorbis("TITLE=x", "lyrics=$japanese"))))
        assertEquals("Línea uno\nLínea dos", read(flac(0 to streamInfo, 4 to vorbis("UNSYNCEDLYRICS=Línea uno\nLínea dos"))))
        assertNull(read(flac(0 to streamInfo, 4 to vorbis("TITLE=x"))))
    }

    // --- MP4 -------------------------------------------------------------------------------------

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return int32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun moovWithLyrics(text: String, fullMeta: Boolean = true): ByteArray {
        val data = box("data", int32(1), int32(0), text.toByteArray())
        val ilst = box("ilst", box("©nam", box("data", int32(1), int32(0), "T".toByteArray())), box("©lyr", data))
        val hdlr = box("hdlr", ByteArray(4), ByteArray(4), "mdir".toByteArray(), ByteArray(12))
        val meta = if (fullMeta) box("meta", ByteArray(4), hdlr, ilst) else box("meta", hdlr, ilst)
        return box("moov", box("mvhd", ByteArray(100)), box("udta", meta))
    }

    @Test
    fun mp4LyricsAtomIsReadWithMoovBeforeOrAfterTheAudio() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        val mdat = box("mdat", ByteArray(5000))
        assertEquals(expected, read(ftyp + moovWithLyrics(japanese) + mdat))
        assertEquals(expected, read(ftyp + mdat + moovWithLyrics(japanese)))
        assertEquals("QuickTime", read(ftyp + moovWithLyrics("QuickTime", fullMeta = false) + mdat))
    }

    @Test
    fun mp4WithoutLyricsGivesNull() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        assertNull(read(ftyp + box("moov", box("mvhd", ByteArray(100))) + box("mdat", ByteArray(100))))
    }

    @Test
    fun unknownFormatsGiveNull() {
        assertNull(read("RIFF....WAVEfmt ".toByteArray() + ByteArray(100)))
        assertNull(read(ByteArray(3)))
    }

    // --- Malformed files: never throw (the lyrics view reads them on demand) -------------------

    @Test
    fun mp4WithAnImpossibleBoxSizeGivesNull() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        fun header(size: Int, type: String) = int32(size) + type.toByteArray()
        // 32-bit size smaller than the 8-byte header itself.
        assertNull(read(ftyp + header(4, "moov") + ByteArray(64)))
        // 64-bit size (size field 1) smaller than the 16-byte header.
        assertNull(read(ftyp + header(1, "moov") + ByteArray(8) + ByteArray(64)))
        // Size 0 ("box extends to the end of the file") on moov.
        assertNull(read(ftyp + header(0, "moov") + ByteArray(64)))
    }

    @Test
    fun id3v23WithANegativeExtendedHeaderSizeGivesNull() {
        val frames = byteArrayOf(0x40) + ByteArray(40) // only the flag matters; content is garbage
        val body = int32(-16) + frames
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x40) + synchsafe(body.size) + body
        assertNull(read(tag + audio))
    }
}
