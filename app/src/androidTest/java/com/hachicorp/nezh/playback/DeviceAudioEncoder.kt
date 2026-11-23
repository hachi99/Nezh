package com.hachicorp.nezh.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates format fixtures on the device with Android's own encoders (no external tools).
 * Each function returns false when the device has no encoder/muxer for that format.
 */
internal object DeviceAudioEncoder {

    fun aacM4a(file: File, seconds: Int): Boolean {
        val format = aacFormat()
        if (!hasEncoder(format)) return false
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        encode(format, AAC_RATE, AAC_CHANNELS, seconds,
            onFormat = { track = muxer.addTrack(it); muxer.start() },
            onSample = { buffer, info -> muxer.writeSampleData(track, buffer, info) })
        muxer.stop()
        muxer.release()
        return true
    }

    /** Raw AAC with an ADTS header per frame (".aac" files). */
    fun aacAdts(file: File, seconds: Int): Boolean {
        val format = aacFormat()
        if (!hasEncoder(format)) return false
        FileOutputStream(file).use { out ->
            encode(format, AAC_RATE, AAC_CHANNELS, seconds, onFormat = {}, onSample = { buffer, info ->
                val frame = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(frame)
                out.write(adtsHeader(frame.size))
                out.write(frame)
            })
        }
        return true
    }

    /** Opus in Ogg. MediaMuxer can write Ogg only from API 29. */
    fun opusOgg(file: File, seconds: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        if (!hasEncoder(format)) return false
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        encode(format, OPUS_RATE, 1, seconds,
            onFormat = { track = muxer.addTrack(it); muxer.start() },
            onSample = { buffer, info -> muxer.writeSampleData(track, buffer, info) })
        muxer.stop()
        muxer.release()
        return true
    }

    /** Decoder names available on this device for [mime] (empty if none). */
    fun decoders(mime: String): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { it.name }

    private fun aacFormat() = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AAC_RATE, AAC_CHANNELS).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
    }

    private fun hasEncoder(format: MediaFormat) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null

    private fun encode(
        format: MediaFormat,
        rate: Int,
        channels: Int,
        seconds: Int,
        onFormat: (MediaFormat) -> Unit,
        onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        val codec = MediaCodec.createByCodecName(name)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val totalFrames = rate * seconds
        var framesQueued = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val frames = minOf(input.remaining() / (2 * channels), totalFrames - framesQueued, 1024)
                        for (i in 0 until frames) {
                            val sample = (8000 * sin(2 * PI * 440 * (framesQueued + i) / rate)).toInt().toShort()
                            repeat(channels) { input.putShort(sample) }
                        }
                        val timeUs = framesQueued * 1_000_000L / rate
                        framesQueued += frames
                        inputDone = framesQueued >= totalFrames
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, frames * 2 * channels, timeUs, flags)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) onSample(buffer, info)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    private fun adtsHeader(payload: Int): ByteArray {
        val length = payload + 7
        val profile = 1 // AAC LC (object type 2) - 1
        val rateIndex = 4 // 44100 Hz
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (rateIndex shl 2) or (AAC_CHANNELS shr 2)).toByte(),
            (((AAC_CHANNELS and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private const val AAC_RATE = 44_100
    private const val AAC_CHANNELS = 1
    private const val OPUS_RATE = 48_000
}
