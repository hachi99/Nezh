package com.hachicorp.nezh.playback.alac

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import com.beatofthedrum.alacdecoder.AlacPacketDecoder
import com.beatofthedrum.alacdecoder.AlacPacketDecoder.AlacDecodeException

/**
 * Audio renderer for ALAC (16 or 24 bit, mono or stereo) with [AlacDecoder]. Nezh places it after
 * the MediaCodec audio renderer (see NezhRenderersFactory), so the device's own ALAC decoder, when
 * there is one, keeps priority; this renderer only plays ALAC the device cannot. Any other ALAC
 * configuration is reported as unsupported, as before.
 */
@OptIn(UnstableApi::class)
class AlacAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<AlacDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = TAG

    override fun supportsFormatInternal(format: Format): @C.FormatSupport Int {
        if (!MimeTypes.AUDIO_ALAC.equals(format.sampleMimeType, ignoreCase = true)) return C.FORMAT_UNSUPPORTED_TYPE
        val config = try {
            AlacPacketDecoder.parseCookie(format.initializationData.singleOrNull())
        } catch (e: AlacDecodeException) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        val output = Util.getPcmFormat(AlacDecoder.encodingFor(config.bitDepth), config.channels, config.sampleRate)
        return when {
            !sinkSupportsFormat(output) -> C.FORMAT_UNSUPPORTED_SUBTYPE
            format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
            else -> C.FORMAT_HANDLED
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacDecoder =
        AlacDecoder(NUM_BUFFERS, NUM_BUFFERS, format.maxInputSize, format.initializationData)

    override fun getOutputFormat(decoder: AlacDecoder): Format =
        Util.getPcmFormat(decoder.pcmEncoding, decoder.channelCount, decoder.sampleRate)

    private companion object {
        const val TAG = "AlacAudioRenderer"
        const val NUM_BUFFERS = 16
    }
}
