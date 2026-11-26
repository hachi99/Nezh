package com.hachicorp.nezh.playback.alac

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** ALAC test inputs: fixtures (androidTest assets, shared with the instrumented tests) and frames built here. */
internal object AlacTestFiles {

    fun asset(path: String) = File("src/androidTest/assets", path)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The first ALAC track of an MP4 file as Media3's MP4 extractor hands it to a decoder: the
     * magic cookie (body of the 'alac' box after its full-box header, Format.initializationData)
     * and one packet per sample, with its duration in PCM frames.
     */
    class Track(val cookie: ByteArray, val packets: List<ByteArray>, val durations: List<Int>)

    fun track(path: String): Track {
        val f = asset(path).readBytes()
        val stbl = Box.path(f, 0, f.size, "moov", "trak", "mdia", "minf", "stbl")
        val stsd = Box.find(f, stbl.body, stbl.end, "stsd")
        val entry = Box.at(f, stsd.body + 8)
        check(entry.type == "alac") { "not ALAC: ${entry.type}" }
        val config = Box.find(f, entry.body + 28, entry.end, "alac")
        val cookie = f.copyOfRange(config.body + 4, config.end)

        val stsz = Box.find(f, stbl.body, stbl.end, "stsz")
        val fixed = u32(f, stsz.body + 4)
        val count = u32(f, stsz.body + 8)
        val sizes = List(count) { if (fixed != 0) fixed else u32(f, stsz.body + 12 + 4 * it) }
        val stts = Box.find(f, stbl.body, stbl.end, "stts")
        val durations = ArrayList<Int>()
        repeat(u32(f, stts.body + 4)) { e ->
            repeat(u32(f, stts.body + 8 + 8 * e)) { durations += u32(f, stts.body + 12 + 8 * e) }
        }
        val stco = Box.find(f, stbl.body, stbl.end, "stco")
        val chunkOffsets = List(u32(f, stco.body + 4)) { u32(f, stco.body + 8 + 4 * it) }
        val stsc = Box.find(f, stbl.body, stbl.end, "stsc")
        val entries = u32(f, stsc.body + 4)
        val packets = ArrayList<ByteArray>()
        for (e in 0 until entries) {
            val firstChunk = u32(f, stsc.body + 8 + 12 * e)
            val perChunk = u32(f, stsc.body + 12 + 12 * e)
            val lastChunk = if (e + 1 < entries) u32(f, stsc.body + 8 + 12 * (e + 1)) - 1 else chunkOffsets.size
            for (chunk in firstChunk..lastChunk) {
                var offset = chunkOffsets[chunk - 1]
                repeat(perChunk) {
                    if (packets.size < count) {
                        packets += f.copyOfRange(offset, offset + sizes[packets.size])
                        offset += sizes[packets.size - 1]
                    }
                }
            }
        }
        check(packets.size == count && durations.size == count)
        return Track(cookie, packets, durations)
    }

    /** PCM data chunk of a WAV file. */
    fun wavData(path: String): ByteArray {
        val d = asset(path).readBytes()
        var pos = 12
        while (pos + 8 <= d.size) {
            val id = String(d, pos, 4, Charsets.ISO_8859_1)
            val size = le32(d, pos + 4)
            if (id == "data") return d.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $path")
    }

    /** ALACSpecificConfig with the given values (Apple's defaults for pb, mb, kb). */
    fun cookie(frameLength: Int, bitDepth: Int, channels: Int, sampleRate: Int = 44100, kb: Int = 14): ByteArray =
        ByteArray(24).also {
            putInt(it, 0, frameLength)
            it[5] = bitDepth.toByte()
            it[6] = 40
            it[7] = 10
            it[8] = kb.toByte()
            it[9] = channels.toByte()
            it[11] = 255.toByte()
            putInt(it, 20, sampleRate)
        }

    /** Random PCM, [frames][channels], in the signed range of [bits]. */
    fun randomPcm(frames: Int, channels: Int, bits: Int, random: Random): Array<IntArray> =
        Array(frames) { IntArray(channels) { random.nextInt(-(1 shl (bits - 1)), 1 shl (bits - 1)) } }

    /** Interleaved little-endian bytes of [pcm]. */
    fun littleEndian(pcm: Array<IntArray>, bits: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in pcm) for (sample in frame) for (b in 0 until bits / 8) out.write(sample shr (8 * b))
        return out.toByteArray()
    }

    /**
     * One ALAC frame in uncompressed ("escape") mode: an SCE (mono) or CPE (stereo) element whose
     * samples are stored verbatim, then END, byte-aligned. [hasSize] writes the sample count (a
     * partial frame).
     */
    fun escapeFrame(pcm: Array<IntArray>, bits: Int, hasSize: Boolean): ByteArray {
        val w = BitWriter()
        w.write(if (pcm[0].size == 1) 0 else 1, 3)
        w.write(0, 4)
        w.write(0, 12)
        w.write(if (hasSize) 1 else 0, 1)
        w.write(0, 2)
        w.write(1, 1)
        if (hasSize) w.write(pcm.size, 32)
        for (frame in pcm) for (sample in frame) w.write(sample, bits)
        w.write(7, 3)
        return w.bytes()
    }

    /** A copy of [packet] with [count] bits from [firstBit] (0 = most significant bit of byte 0) set to [value]. */
    fun withBits(packet: ByteArray, firstBit: Int, count: Int, value: Int): ByteArray = packet.copyOf().also {
        for (i in 0 until count) {
            val bit = firstBit + i
            val mask = 0x80 ushr (bit and 7)
            val on = (value ushr (count - 1 - i)) and 1 == 1
            it[bit ushr 3] = (if (on) it[bit ushr 3].toInt() or mask else it[bit ushr 3].toInt() and mask.inv()).toByte()
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var used = 0

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                if (++used == 8) {
                    out.write(current)
                    current = 0
                    used = 0
                }
            }
        }

        fun bytes(): ByteArray {
            if (used > 0) write(0, 8 - used)
            return out.toByteArray()
        }
    }

    private class Box(val type: String, val body: Int, val end: Int) {
        companion object {
            fun at(f: ByteArray, pos: Int): Box {
                val size = u32(f, pos)
                check(size >= 8 && pos + size <= f.size) { "bad box at $pos" }
                return Box(String(f, pos + 4, 4, Charsets.ISO_8859_1), pos + 8, pos + size)
            }

            fun find(f: ByteArray, from: Int, to: Int, type: String): Box {
                var pos = from
                while (pos + 8 <= to) {
                    val box = at(f, pos)
                    if (box.type == type) return box
                    pos = box.end
                }
                error("no $type box")
            }

            fun path(f: ByteArray, from: Int, to: Int, vararg types: String): Box {
                var box = find(f, from, to, types[0])
                for (type in types.drop(1)) box = find(f, box.body, box.end, type)
                return box
            }
        }
    }

    private fun u32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0..3) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }
}
