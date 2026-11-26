package com.hachicorp.nezh.playback.alac

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.beatofthedrum.alacdecoder.AlacPacketDecoder
import com.beatofthedrum.alacdecoder.AlacPacketDecoder.AlacDecodeException

/** A malformed or unsupported ALAC packet or magic cookie. Reported as a decoding error. */
@OptIn(UnstableApi::class)
class AlacDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * ALAC decoder for Media3, used where Android has no ALAC decoder of its own (see
 * [AlacAudioRenderer]). Each input buffer is one MP4 sample (one ALAC packet); the magic cookie
 * comes from Format.initializationData. The work is done by the Java Apple Lossless Decoder core
 * through [AlacPacketDecoder]; packets are independent, so flush and seek need nothing here.
 *
 * @param maxInputSize largest packet in bytes, or [Format.NO_VALUE]
 * @throws AlacDecoderException if the magic cookie is missing, invalid or unsupported
 */
// SimpleDecoder fills both arrays in its constructor (createInputBuffer/createOutputBuffer).
@Suppress("UNCHECKED_CAST")
@OptIn(UnstableApi::class)
class AlacDecoder(
    numInputBuffers: Int,
    numOutputBuffers: Int,
    maxInputSize: Int,
    initializationData: List<ByteArray>,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(numInputBuffers) as Array<DecoderInputBuffer>,
    arrayOfNulls<SimpleDecoderOutputBuffer>(numOutputBuffers) as Array<SimpleDecoderOutputBuffer>,
) {
    private val packets: AlacPacketDecoder
    private val pcm: ByteArray

    /** Output format: 16 or 24-bit PCM ([C.ENCODING_PCM_16BIT] or [C.ENCODING_PCM_24BIT]). */
    val pcmEncoding: Int
    val channelCount: Int
    val sampleRate: Int

    init {
        val cookie = initializationData.singleOrNull()
            ?: throw AlacDecoderException("expected one magic cookie, got ${initializationData.size} initialization data entries")
        packets = try {
            AlacPacketDecoder(cookie)
        } catch (e: AlacDecodeException) {
            throw AlacDecoderException("invalid ALAC magic cookie: ${e.message}", e)
        }
        val config = packets.config()
        pcmEncoding = encodingFor(config.bitDepth)
        channelCount = config.channels
        sampleRate = config.sampleRate
        pcm = ByteArray(config.maxOutputBytes())
        setInitialInputBufferSize(
            when {
                maxInputSize != Format.NO_VALUE -> maxInputSize
                config.maxFrameBytes > 0 -> config.maxFrameBytes
                else -> config.maxOutputBytes() + FRAME_OVERHEAD_BYTES
            },
        )
    }

    override fun getName(): String = NAME

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer = SimpleDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable): AlacDecoderException =
        AlacDecoderException("unexpected ALAC decoding error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): AlacDecoderException? {
        val data = inputBuffer.data ?: return AlacDecoderException("input buffer without data")
        val frames = try {
            packets.decode(data, pcm)
        } catch (e: AlacDecodeException) {
            return AlacDecoderException("ALAC packet at ${inputBuffer.timeUs} us: ${e.message}", e)
        }
        val bytes = frames * channelCount * bytesPerSample(pcmEncoding)
        outputBuffer.init(inputBuffer.timeUs, bytes).apply {
            put(pcm, 0, bytes)
            flip()
        }
        return null
    }

    companion object {
        /** Reported by Media3 as the audio decoder name (AnalyticsListener, logs). */
        const val NAME = "nezh-java-alac"

        /** Frame header, partial-frame size and END element on top of the samples (uncompressed frame). */
        private const val FRAME_OVERHEAD_BYTES = 16

        /** The PCM encoding for a validated bit depth (16 or 24). */
        fun encodingFor(bitDepth: Int): Int = if (bitDepth == 24) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_16BIT

        private fun bytesPerSample(encoding: Int): Int = if (encoding == C.ENCODING_PCM_24BIT) 3 else 2
    }
}
