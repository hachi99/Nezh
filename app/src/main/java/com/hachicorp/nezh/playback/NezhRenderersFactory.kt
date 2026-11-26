package com.hachicorp.nezh.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.hachicorp.nezh.playback.alac.AlacAudioRenderer

/**
 * Media3's default renderers plus [AlacAudioRenderer], placed right after the MediaCodec audio
 * renderer. Media3 gives a track to the first renderer that handles its format, so the device's
 * own decoders keep priority for every format, ALAC included; the ALAC renderer only takes ALAC
 * the device cannot decode. It shares the same audio sink.
 */
@OptIn(UnstableApi::class)
internal class NezhRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
        val afterMediaCodec = out.indexOfFirst { it is MediaCodecAudioRenderer } + 1
        out.add(afterMediaCodec, AlacAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
