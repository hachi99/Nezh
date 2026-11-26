package com.hachicorp.nezh.playback.alac

import com.beatofthedrum.alacdecoder.AlacPacketDecoder
import com.beatofthedrum.alacdecoder.AlacPacketDecoder.AlacDecodeException
import com.hachicorp.nezh.playback.alac.AlacTestFiles.cookie
import com.hachicorp.nezh.playback.alac.AlacTestFiles.escapeFrame
import com.hachicorp.nezh.playback.alac.AlacTestFiles.littleEndian
import com.hachicorp.nezh.playback.alac.AlacTestFiles.randomPcm
import com.hachicorp.nezh.playback.alac.AlacTestFiles.sha256
import com.hachicorp.nezh.playback.alac.AlacTestFiles.track
import com.hachicorp.nezh.playback.alac.AlacTestFiles.withBits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * The Java ALAC core with Nezh's adapter, packet by packet as Media3 feeds it.
 *
 * Reference PCM: the three compressed fixtures were encoded from deterministic WAV files (see
 * tools/fixtures); the SHA-256 below is that source PCM, so a match means bit-exact decoding.
 * tone_alac.m4a's reference is tone_pcm.wav (its first 43 x 4096 frames).
 */
class AlacPacketDecoderTest {

    private fun decodeAll(path: String): Pair<ByteArray, Int> {
        val track = track(path)
        val decoder = AlacPacketDecoder(track.cookie)
        val out = ByteArray(decoder.config().maxOutputBytes())
        val pcm = ByteArrayOutputStream()
        var frames = 0
        track.packets.forEachIndexed { i, packet ->
            val n = decoder.decode(packet, 0, packet.size, out)
            assertEquals("packet $i frames", track.durations[i], n)
            frames += n
            pcm.write(out, 0, n * frameBytes(decoder))
        }
        return pcm.toByteArray() to frames
    }

    private fun frameBytes(d: AlacPacketDecoder) = d.config().channels * d.config().bitDepth / 8

    private fun assertRejected(message: String, block: () -> Unit) {
        try {
            block()
            fail("$message: accepted")
        } catch (e: AlacDecodeException) {
            // expected: a controlled decoding error
        }
    }

    @Test
    fun sixteenBitStereoIsBitExact() {
        val (pcm, frames) = decodeAll("formats/alac_16_44k_stereo.m4a")
        assertEquals(176_400, frames)
        assertEquals("01d15ed90a8d2c85c3abff095512a75e244e5f684be7ef81a8cb9c2885fa2fb8", sha256(pcm))
    }

    @Test
    fun sixteenBitMonoIsBitExact() {
        val (pcm, frames) = decodeAll("formats/alac_16_48k_mono.m4a")
        assertEquals(144_000, frames)
        assertEquals("83025d3dadef1109be73550de9b41ea950e1ef8fc710e30cdef79812618e146e", sha256(pcm))
    }

    @Test
    fun twentyFourBitStereoIsBitExact() {
        val (pcm, frames) = decodeAll("formats/alac_24_96k_stereo.m4a")
        assertEquals(288_000, frames)
        assertEquals("5054927135f6f4850db9f317e456e80813047466c1638413a5e533a65173eaf7", sha256(pcm))
    }

    @Test
    fun uncompressedFramesMatchTheirSource() {
        val (pcm, frames) = decodeAll("codecs/tone_alac.m4a")
        assertEquals(43 * 4096, frames)
        assertArrayEquals(AlacTestFiles.wavData("codecs/tone_pcm.wav").copyOf(pcm.size), pcm)
    }

    /** Every bit depth and channel count, frame lengths 1 to 16384, full and partial frames. */
    @Test
    fun syntheticFramesOfEverySupportedConfigurationAreExact() {
        for (bits in intArrayOf(16, 24)) for (channels in intArrayOf(1, 2)) for (frameLength in intArrayOf(1, 2, 4096, 16384)) {
            val random = Random(bits * 100 + channels * 10 + frameLength)
            val decoder = AlacPacketDecoder(cookie(frameLength, bits, channels))
            val out = ByteArray(decoder.config().maxOutputBytes())
            val sizes = if (frameLength > 1) listOf(frameLength, frameLength, frameLength / 2 + 1) else listOf(1, 1)
            for (samples in sizes) {
                val pcm = randomPcm(samples, channels, bits, random)
                val packet = escapeFrame(pcm, bits, hasSize = samples != frameLength)
                val n = decoder.decode(packet, 0, packet.size, out)
                val label = "$bits bit, $channels ch, frame $frameLength, $samples samples"
                assertEquals(label, samples, n)
                assertArrayEquals(label, littleEndian(pcm, bits), out.copyOf(n * channels * bits / 8))
            }
        }
    }

    @Test
    fun theLastPartialFrameDecodesItsDeclaredSamples() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val last = track.packets.last()
        val decoder = AlacPacketDecoder(track.cookie)
        assertEquals(176_400 - 43 * 4096, track.durations.last())
        assertEquals(track.durations.last(), decoder.decode(last, 0, last.size, ByteArray(decoder.config().maxOutputBytes())))
    }

    /** Seeking: Media3 can start at any packet, so each must decode alone to the same bytes. */
    @Test
    fun everyPacketDecodesAloneLikeInSequence() {
        for (path in listOf("formats/alac_16_44k_stereo.m4a", "formats/alac_24_96k_stereo.m4a", "formats/alac_16_48k_mono.m4a")) {
            val track = track(path)
            val sequential = AlacPacketDecoder(track.cookie)
            val size = sequential.config().maxOutputBytes()
            val expected = track.packets.map { p -> ByteArray(size).let { it.copyOf(sequential.decode(p, 0, p.size, it) * frameBytes(sequential)) } }
            val reused = AlacPacketDecoder(track.cookie)
            for (i in track.packets.indices.reversed()) {
                val p = track.packets[i]
                val fresh = AlacPacketDecoder(track.cookie)
                val a = ByteArray(size).let { it.copyOf(fresh.decode(p, 0, p.size, it) * frameBytes(fresh)) }
                val b = ByteArray(size).let { it.copyOf(reused.decode(p, 0, p.size, it) * frameBytes(reused)) }
                assertArrayEquals("$path packet $i, fresh decoder", expected[i], a)
                assertArrayEquals("$path packet $i, reused decoder", expected[i], b)
            }
        }
    }

    /** Direct buffers, a non-zero position and a backing array offset all give the same PCM. */
    @Test
    fun byteBufferInputRespectsPositionLimitAndOffset() {
        val track = track("formats/alac_24_96k_stereo.m4a")
        val packet = track.packets[3]
        val decoder = AlacPacketDecoder(track.cookie)
        val expected = ByteArray(decoder.config().maxOutputBytes()).let { it.copyOf(decoder.decode(packet, 0, packet.size, it) * 6) }

        val direct = ByteBuffer.allocateDirect(packet.size).put(packet).also { it.flip() }
        val shifted = ByteBuffer.wrap(ByteArray(packet.size + 20).also { packet.copyInto(it, 7) }, 7, packet.size)
        val sliced = ByteBuffer.wrap(ByteArray(packet.size + 9).also { packet.copyInto(it, 5) })
            .also { it.position(5) }.slice().also { it.limit(packet.size) }
        check(sliced.arrayOffset() == 5)
        for ((name, buffer) in listOf("direct" to direct, "position 7" to shifted, "array offset 5" to sliced)) {
            val position = buffer.position()
            val out = ByteArray(decoder.config().maxOutputBytes())
            val n = decoder.decode(buffer, out)
            assertArrayEquals(name, expected, out.copyOf(n * 6))
            assertEquals("$name: position unchanged", position, buffer.position())
        }
    }

    @Test
    fun truncatedPacketsAreRejected() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val decoder = AlacPacketDecoder(track.cookie)
        val out = ByteArray(decoder.config().maxOutputBytes())
        for (index in listOf(0, 10, 43)) {
            val packet = track.packets[index]
            for (length in listOf(0, 1, 2, 3, packet.size / 4, packet.size / 2, packet.size * 3 / 4, packet.size - 8)) {
                assertRejected("packet $index cut to $length bytes") { decoder.decode(packet, 0, length, out) }
            }
        }
    }

    @Test
    fun invalidOrUnsupportedCookiesAreRejected() {
        val valid = cookie(4096, 16, 2)
        val cases = mapOf(
            "too short" to valid.copyOf(23),
            "frame length 0" to cookie(0, 16, 2),
            "frame length 16385" to cookie(16385, 16, 2),
            "bit depth 8" to cookie(4096, 8, 2),
            "bit depth 20" to cookie(4096, 20, 2),
            "bit depth 32" to cookie(4096, 32, 2),
            "0 channels" to cookie(4096, 16, 0),
            "3 channels" to cookie(4096, 16, 3),
            "8 channels" to cookie(4096, 16, 8),
            "sample rate 0" to cookie(4096, 16, 2, sampleRate = 0),
            "compatible version 1" to valid.copyOf().also { it[4] = 1 },
            "Rice kb 0" to cookie(4096, 16, 2, kb = 0),
            "Rice kb 25" to cookie(4096, 16, 2, kb = 25),
            "Rice kb 255" to cookie(4096, 16, 2, kb = 255),
        )
        for ((name, c) in cases) assertRejected(name) { AlacPacketDecoder.parseCookie(c) }
        assertRejected("no cookie") { AlacPacketDecoder.parseCookie(null) }
        AlacPacketDecoder.parseCookie(valid) // and a valid one is accepted
    }

    @Test
    fun oversizedFramesAreRejected() {
        val pcm = randomPcm(4097, 2, 16, Random(1))
        val decoder = AlacPacketDecoder(cookie(4096, 16, 2))
        val packet = escapeFrame(pcm, 16, hasSize = true)
        assertRejected("frame of 4097 samples, cookie of 4096") { decoder.decode(packet, 0, packet.size, ByteArray(1 shl 20)) }
        val full = escapeFrame(randomPcm(4096, 2, 16, Random(2)), 16, hasSize = false)
        val huge = withBits(withBits(full, 19, 1, 1), 23, 32, Int.MAX_VALUE) // claims a size field of 2^31-1
        assertRejected("frame claiming 2^31-1 samples") { decoder.decode(huge, 0, huge.size, ByteArray(1 shl 20)) }
    }

    /** D2: the core used to print a message to standard error and return wrong samples. */
    @Test
    fun unsupportedPredictionTypeIsAnErrorNotAudio() {
        for (path in listOf("formats/alac_16_44k_stereo.m4a", "formats/alac_16_48k_mono.m4a", "formats/alac_24_96k_stereo.m4a")) {
            val track = track(path)
            val decoder = AlacPacketDecoder(track.cookie)
            // Bits 39-42: the (first) prediction type of a frame without a size field.
            val packet = withBits(track.packets[5], 39, 4, 1)
            try {
                decoder.decode(packet, 0, packet.size, ByteArray(decoder.config().maxOutputBytes()))
                fail("$path: accepted")
            } catch (e: AlacDecodeException) {
                assertTrue("$path: ${e.message}", e.message!!.contains("prediction type"))
            }
        }
        // The fixture built for the playback tests carries this defect in packet 5.
        val bad = track("formats/alac_bad_prediction.m4a")
        val decoder = AlacPacketDecoder(bad.cookie)
        assertRejected("alac_bad_prediction.m4a packet 5") { decoder.decode(bad.packets[5], 0, bad.packets[5].size, ByteArray(1 shl 16)) }
    }

    @Test
    fun malformedHeadersAreRejected() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val decoder = AlacPacketDecoder(track.cookie)
        val p = track.packets[2]
        val out = ByteArray(decoder.config().maxOutputBytes())
        assertRejected("mono element in a stereo stream") { withBits(p, 0, 3, 0).let { decoder.decode(it, 0, it.size, out) } }
        assertRejected("LFE element") { withBits(p, 0, 3, 3).let { decoder.decode(it, 0, it.size, out) } }
        assertRejected("non-zero reserved bits") { withBits(p, 7, 12, 1).let { decoder.decode(it, 0, it.size, out) } }
        assertRejected("uncompressed bytes at 16 bit") { withBits(p, 20, 2, 1).let { decoder.decode(it, 0, it.size, out) } }
    }

    /**
     * D1/D5 and every other malformation: single bit flips and random bytes never escape as
     * anything but a decoding error (no ArrayIndexOutOfBounds, no hang); data the format cannot
     * check (the audio payload has no checksum) may decode to different samples.
     */
    @Test(timeout = 60_000)
    fun malformedPacketsOnlyEverFailAsDecodingErrors() {
        val random = Random(20261126)
        var rejected = 0
        for (path in listOf("formats/alac_16_44k_stereo.m4a", "formats/alac_24_96k_stereo.m4a", "formats/alac_16_48k_mono.m4a")) {
            val track = track(path)
            val decoder = AlacPacketDecoder(track.cookie)
            val out = ByteArray(decoder.config().maxOutputBytes())
            repeat(1500) {
                val packet = track.packets[random.nextInt(track.packets.size)].copyOf()
                if (random.nextBoolean()) {
                    val bit = random.nextInt(packet.size * 8)
                    packet[bit ushr 3] = (packet[bit ushr 3].toInt() xor (0x80 ushr (bit and 7))).toByte()
                } else {
                    repeat(8) { packet[random.nextInt(packet.size)] = random.nextInt(256).toByte() }
                }
                try {
                    decoder.decode(packet, 0, packet.size, out)
                } catch (e: AlacDecodeException) {
                    rejected++
                }
            }
        }
        assertTrue("some malformed packets must be detected", rejected > 0)
    }

    /** After a bad packet the same decoder decodes the next packets exactly (no stale state). */
    @Test
    fun decoderRecoversAfterAnError() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val reference = AlacPacketDecoder(track.cookie)
        val size = reference.config().maxOutputBytes()
        val expected = ByteArray(size).let { it.copyOf(reference.decode(track.packets[6], 0, track.packets[6].size, it) * 4) }

        val decoder = AlacPacketDecoder(track.cookie)
        val bad = withBits(track.packets[5], 39, 4, 1)
        assertRejected("bad packet") { decoder.decode(bad, 0, bad.size, ByteArray(size)) }
        val truncated = track.packets[5]
        assertRejected("truncated packet") { decoder.decode(truncated, 0, truncated.size / 2, ByteArray(size)) }
        val out = ByteArray(size)
        val n = decoder.decode(track.packets[6], 0, track.packets[6].size, out)
        assertArrayEquals(expected, out.copyOf(n * 4))
    }
}
