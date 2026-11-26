package com.hachicorp.nezh.playback.alac

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.hachicorp.nezh.playback.alac.AlacTestFiles.sha256
import com.hachicorp.nezh.playback.alac.AlacTestFiles.track
import com.hachicorp.nezh.playback.alac.AlacTestFiles.withBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** [AlacDecoder] through Media3's decoder API: queue input, dequeue output, flush, errors, release. */
@OptIn(UnstableApi::class)
class AlacDecoderTest {

    private fun decoder(cookie: ByteArray, maxInputSize: Int = Format.NO_VALUE) = AlacDecoder(4, 4, maxInputSize, listOf(cookie))

    /** Queues [packets] (timestamps 1000 us apart) and returns the PCM of every output buffer, in order. */
    private fun run(decoder: AlacDecoder, packets: List<ByteArray>, firstTimeUs: Long = 0): Pair<ByteArray, List<Long>> {
        val pcm = ByteArrayOutputStream()
        val times = ArrayList<Long>()
        var queued = 0
        val deadline = System.currentTimeMillis() + 30_000
        while (times.size < packets.size) {
            check(System.currentTimeMillis() < deadline) { "decoder stalled" }
            if (queued < packets.size) {
                decoder.dequeueInputBuffer()?.let { input ->
                    val packet = packets[queued]
                    input.ensureSpaceForWrite(packet.size)
                    input.data!!.put(packet)
                    input.timeUs = firstTimeUs + 1000L * queued
                    input.flip()
                    decoder.queueInputBuffer(input)
                    queued++
                }
            }
            val output = decoder.dequeueOutputBuffer() ?: continue
            val data = output.data!!
            val bytes = ByteArray(data.remaining()).also { data.get(it) }
            pcm.write(bytes)
            times += output.timeUs
            output.release()
        }
        return pcm.toByteArray() to times
    }

    @Test
    fun decodesAFileThroughMedia3sDecoderApi() {
        val track = track("formats/alac_24_96k_stereo.m4a")
        val decoder = decoder(track.cookie)
        assertEquals(C.ENCODING_PCM_24BIT, decoder.pcmEncoding)
        assertEquals(2, decoder.channelCount)
        assertEquals(96_000, decoder.sampleRate)
        assertEquals("nezh-java-alac", decoder.name)
        val (pcm, times) = run(decoder, track.packets)
        assertEquals("5054927135f6f4850db9f317e456e80813047466c1638413a5e533a65173eaf7", sha256(pcm))
        assertEquals(List(track.packets.size) { 1000L * it }, times)
        decoder.release()
    }

    @Test
    fun sixteenBitOutputIsPcm16() {
        val track = track("formats/alac_16_48k_mono.m4a")
        val decoder = decoder(track.cookie, maxInputSize = track.packets.maxOf { it.size })
        assertEquals(C.ENCODING_PCM_16BIT, decoder.pcmEncoding)
        assertEquals(1, decoder.channelCount)
        assertEquals("83025d3dadef1109be73550de9b41ea950e1ef8fc710e30cdef79812618e146e", sha256(run(decoder, track.packets).first))
        decoder.release()
    }

    /** A seek flushes the decoder; decoding then resumes at any packet. */
    @Test
    fun afterFlushDecodingResumesAtAnyPacket() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val reference = decoder(track.cookie)
        val (all, _) = run(reference, track.packets)
        reference.release()

        val decoder = decoder(track.cookie)
        run(decoder, track.packets.subList(0, 3))
        decoder.flush()
        val (tail, times) = run(decoder, track.packets.subList(30, track.packets.size), firstTimeUs = 30_000)
        assertEquals(all.copyOfRange(30 * 4096 * 4, all.size).toList(), tail.toList())
        assertEquals(30_000L, times.first())
        decoder.release()
    }

    @Test
    fun aBadPacketIsReportedAsAlacDecoderException() {
        val track = track("formats/alac_16_44k_stereo.m4a")
        val decoder = decoder(track.cookie)
        try {
            run(decoder, listOf(track.packets[0], withBits(track.packets[5], 39, 4, 1)))
            fail("bad packet accepted")
        } catch (e: AlacDecoderException) {
            assertTrue(e.message, e.message!!.contains("prediction type"))
        }
        decoder.release()
    }

    @Test
    fun anInvalidCookieFailsWhenTheDecoderIsCreated() {
        for (initializationData in listOf(emptyList(), listOf(ByteArray(10)), listOf(AlacTestFiles.cookie(4096, 20, 2)))) {
            try {
                AlacDecoder(4, 4, Format.NO_VALUE, initializationData)
                fail("accepted $initializationData")
            } catch (e: AlacDecoderException) {
                // expected
            }
        }
    }
}
