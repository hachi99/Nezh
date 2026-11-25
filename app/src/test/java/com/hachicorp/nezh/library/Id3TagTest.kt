package com.hachicorp.nezh.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3TagTest {

    // --- builders ------------------------------------------------------------------------------

    private fun synchsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun int32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun text(encoding: Int, value: String): ByteArray = byteArrayOf(encoding.toByte()) + when (encoding) {
        0 -> value.toByteArray(Charsets.ISO_8859_1)
        1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        2 -> value.toByteArray(Charsets.UTF_16BE)
        else -> value.toByteArray(Charsets.UTF_8)
    }

    private fun frame(version: Int, id: String, body: ByteArray, formatFlags: Int = 0): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + (if (version == 4) synchsafe(body.size) else int32(body.size)) +
            byteArrayOf(0, formatFlags.toByte()) + body

    private fun tag(version: Int, vararg frames: ByteArray, flags: Int = 0, padding: Int = 16): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, f -> a + f } + ByteArray(padding)
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, flags.toByte()) +
            synchsafe(body.size) + body
    }

    private fun apic(type: Int, data: ByteArray, encoding: Int = 0, description: String = "") =
        byteArrayOf(encoding.toByte()) + "image/png".toByteArray() + byte(0) + byteArrayOf(type.toByte()) +
            (if (encoding == 1) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + description.toByteArray(Charsets.UTF_16LE) + byte(0) + byte(0)
            else description.toByteArray(Charsets.ISO_8859_1) + byte(0)) + data

    private fun byte(v: Int) = byteArrayOf(v.toByte())
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(200)

    private fun read(bytes: ByteArray, wantPicture: Boolean = true) = Id3Tag.read((bytes + audio).inputStream(), wantPicture)

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun utf16WithLatinRangeAccentsNextToOtherScriptsIsReadExactly() {
        // The case the platform reader garbles: one field all <= U+00FF, another beyond.
        val nfdArtist = "Dvořák"
        val result = read(tag(3, frame(3, "TIT2", text(1, "Ñandú Zoë")), frame(3, "TPE1", text(1, nfdArtist)), frame(3, "TALB", text(1, "Ångström"))))!!
        assertEquals("Ñandú Zoë", result.title!!.value)
        assertEquals(nfdArtist, result.artist!!.value) // decomposed form kept as stored
        assertEquals("Ångström", result.album!!.value)
        assertTrue(result.title!!.declaredUnicode)
    }

    @Test
    fun everyDeclaredEncodingIsDecodedAsDeclared() {
        val cases = mapOf(0 to "Beyoncé", 1 to "山田太郎", 2 to "봄날의 기억", 3 to "أغنية الليل 👩‍🎤")
        for ((encoding, value) in cases) {
            for (version in listOf(3, 4)) {
                if (version == 3 && encoding >= 2) continue // UTF-16BE / UTF-8 are ID3v2.4 only
                val result = read(tag(version, frame(version, "TPE1", text(encoding, value))))!!
                assertEquals("v2.$version enc $encoding", value, result.artist!!.value)
                assertEquals(encoding != 0, result.artist!!.declaredUnicode)
            }
        }
    }

    @Test
    fun utf16WithoutByteOrderMarkIsBigEndianAsTheSpecSays() {
        val body = byteArrayOf(1) + "Ночь".toByteArray(Charsets.UTF_16BE)
        assertEquals("Ночь", read(tag(3, frame(3, "TIT2", body)))!!.title!!.value)
    }

    @Test
    fun severalValuesInOneFrameAreKeptTogether() {
        val result = read(tag(4, frame(4, "TPE1", text(3, "Björk\u0000坂本龍一"))))!!
        assertEquals("Björk / 坂本龍一", result.artist!!.value)
    }

    @Test
    fun tagLevelUnsynchronisationIsUndone() {
        // UTF-16 BOM FF FE must be unsynchronised to FF 00 FE by the writer.
        val frames = frame(3, "TIT2", text(1, "さくら")) + frame(3, "TPE1", text(1, "ÿÿ Artist"))
        val raw = tag(3, frames)
        val body = raw.copyOfRange(10, raw.size)
        val unsync = ByteArrayOutputStream()
        body.forEachIndexed { i, b ->
            unsync.write(b.toInt())
            if (b == 0xFF.toByte() && (i + 1 == body.size || (body[i + 1].toInt() and 0xFF) >= 0xE0 || body[i + 1] == 0.toByte())) unsync.write(0)
        }
        val unsynchronised = raw.copyOfRange(0, 5) + byte(0x80) + synchsafe(unsync.size()) + unsync.toByteArray()
        val result = read(unsynchronised)!!
        assertEquals("さくら", result.title!!.value)
        assertEquals("ÿÿ Artist", result.artist!!.value)
    }

    @Test
    fun v24FrameUnsynchronisationAndDataLengthIndicatorAreHandled() {
        val body = text(1, "Морской")
        val unsync = Id3Tag.removeUnsynchronisation(body) // no FF 00 pairs: unchanged
        assertArrayEquals(body, unsync)
        val withIndicator = synchsafe(body.size) + body
        val result = read(tag(4, frame(4, "TALB", withIndicator, formatFlags = 0x01)))!!
        assertEquals("Морской", result.album!!.value)
    }

    @Test
    fun extendedHeaderIsSkipped() {
        val ext3 = int32(6) + ByteArray(6)
        val v3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x40)
        val body = ext3 + frame(3, "TIT2", text(1, "Ελλάδα")) + ByteArray(8)
        assertEquals("Ελλάδα", read(v3 + synchsafe(body.size) + body)!!.title!!.value)

        val ext4 = synchsafe(6) + byteArrayOf(1, 0)
        val v4 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0x40)
        val body4 = ext4 + frame(4, "TIT2", text(3, "Ελλάδα")) + ByteArray(8)
        assertEquals("Ελλάδα", read(v4 + synchsafe(body4.size) + body4)!!.title!!.value)
    }

    @Test
    fun compressedOrEncryptedFramesAreSkippedNotMisread() {
        val result = read(tag(3, frame(3, "TIT2", text(1, "secret"), formatFlags = 0x80), frame(3, "TPE1", text(1, "visible"))))!!
        assertNull(result.title)
        assertEquals("visible", result.artist!!.value)
    }

    @Test
    fun frontCoverIsPreferredAndReadEvenFromLargeTags() {
        val other = ByteArray(1000) { 1 }
        val front = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() } // larger than the platform's limit
        val result = read(tag(3, frame(3, "APIC", apic(0, other)), frame(3, "APIC", apic(3, front, encoding = 1, description = "表紙")), frame(3, "TIT2", text(1, "Morning Light"))))!!
        assertArrayEquals(front, result.picture)
        assertEquals("Morning Light", result.title!!.value)
        assertNull(read(tag(3, frame(3, "APIC", apic(3, front))), wantPicture = false)!!.picture)
    }

    @Test
    fun malformedTextIsRejectedInsteadOfReplaced() {
        val badUtf8 = byteArrayOf(3, 0xC3.toByte(), 0x28) // invalid UTF-8 sequence
        assertNull(read(tag(4, frame(4, "TIT2", badUtf8)))!!.title)
    }

    @Test
    fun noTagUnsupportedVersionsAndTruncatedTagsGiveNull() {
        assertNull(Id3Tag.read(audio.inputStream(), true))
        val v22 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 2, 0, 0) + synchsafe(10) + ByteArray(10)
        assertNull(read(v22))
        val full = tag(3, frame(3, "TIT2", text(1, "x")))
        assertNull(Id3Tag.read(full.copyOfRange(0, full.size - 5).inputStream(), true))
        assertNull(Id3Tag.read(ByteArray(3).inputStream(), true))
    }

    @Test
    fun aFrameDeclaringMoreBytesThanTheTagStopsParsingSafely() {
        val broken = "TIT2".toByteArray() + int32(9999) + byteArrayOf(0, 0) + text(1, "x")
        val result = read(tag(3, frame(3, "TPE1", text(1, "ok")), broken))!!
        assertEquals("ok", result.artist!!.value)
        assertNull(result.title)
        assertFalse(result.picture != null)
    }
}
