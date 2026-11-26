package com.hachicorp.nezh.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.playback.alac.AlacDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Format/metadata/duration/seek matrix for the production player ([buildNezhPlayer]).
 *
 * Reproducible on any device: `gradlew connectedDebugAndroidTest` and read the table with
 * `adb logcat -s NezhFormatMatrix`. Fixtures come from tools/fixtures (assets) plus formats encoded
 * on the device itself. Fixture names are synthetic; no user files are read.
 *
 * Only essential formats fail the test; everything else is reported, because codec support
 * legitimately varies between devices and Android versions.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class FormatCompatibilityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "format-fixtures").apply { mkdirs() }

    private data class Fixture(
        val name: String,
        val expectedMs: Long?,
        val expectTags: Boolean = false,
        val essential: Boolean = false,
        val expectError: Boolean = false,
        val note: String = "",
        /** Title the user must see: the tag title, or the file name when there is none. */
        val expectedTitle: String = name.substringBeforeLast('.'),
    )

    private data class Row(
        val fixture: String,
        val config: String,
        val playable: Boolean,
        val decoder: String,
        val error: String,
        val durationMs: Long?,
        val expectedMs: Long?,
        val seekable: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        val artworkBytes: Int,
        val midSeekOk: Boolean,
        val endAfterSeekMs: Long?,
        val fullPlaybackOk: Boolean,
        val decoderInits: Int = 0,
    ) {
        val durationErrorPct: Double?
            get() = if (durationMs != null && expectedMs != null) 100.0 * abs(durationMs - expectedMs) / expectedMs else null
    }

    @Test
    fun formatMatrix() {
        logCodecs()
        val fixtures = mutableListOf(
            Fixture("mp3_cbr_tagged.mp3", 20_010, expectTags = true, essential = true, expectedTitle = "Título de prueba"),
            Fixture("mp3_cbr_untagged.mp3", 20_010, essential = true),
            Fixture("mp3_partial_tags.mp3", 20_010, note = "solo artista"),
            Fixture("mp3_vbr_xing.mp3", 20_010, essential = true),
            Fixture("mp3_vbr_noheader.mp3", 20_010, note = "VBR sin cabecera Xing/VBRI"),
            Fixture("mp3_large_artwork.mp3", 4_989, expectTags = true, note = "carátula 3000x3000", expectedTitle = "Título de prueba"),
            Fixture("flac_tagged.flac", 19_969, expectTags = true, essential = Build.VERSION.SDK_INT >= 27, expectedTitle = "Título FLAC"),
            Fixture("flac_untagged.flac", 19_969, essential = Build.VERSION.SDK_INT >= 27),
            Fixture("wav_pcm16.wav", 5_000),
            // Compressed ALAC: the device's decoder if it has one, else Nezh's Java decoder; essential.
            Fixture("alac_16_44k_stereo.m4a", 4_000, essential = true, note = "ALAC 16 bits 44,1 kHz estéreo"),
            Fixture("alac_24_96k_stereo.m4a", 3_000, essential = true, note = "ALAC 24 bits 96 kHz estéreo"),
            Fixture("alac_16_48k_mono.m4a", 3_000, essential = true, note = "ALAC 16 bits 48 kHz mono"),
            Fixture("unsupported.mid", null, expectError = true),
            Fixture("corrupt.mp3", null, expectError = true),
        )
        fixtures.forEach { copyAsset(it.name) }
        if (DeviceAudioEncoder.aacM4a(File(dir, "m4a_aac.m4a"), 10)) fixtures += Fixture("m4a_aac.m4a", 10_000, essential = true)
        if (DeviceAudioEncoder.aacAdts(File(dir, "aac_adts.aac"), 10)) fixtures += Fixture("aac_adts.aac", 10_000)
        if (DeviceAudioEncoder.opusOgg(File(dir, "opus.ogg"), 10)) fixtures += Fixture("opus.ogg", 10_000)

        val rows = mutableListOf<Row>()
        for (fixture in fixtures) {
            rows += measure(fixture, "prod") { buildNezhPlayer(it) }
        }
        // Alternatives considered for the production configuration, measured for comparison.
        rows += measure(fixtures.first { it.name == "mp3_vbr_noheader.mp3" }, "mp3-index") {
            playerWith(it, DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING))
        }
        fixtures.firstOrNull { it.name == "aac_adts.aac" }?.let { adts ->
            rows += measure(adts, "cbr-seek") {
                playerWith(it, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            }
        }

        logMatrix(rows)

        val failures = mutableListOf<String>()
        for (fixture in fixtures) {
            val row = rows.first { it.fixture == fixture.name && it.config == "prod" }
            if (fixture.essential && !(row.playable && row.fullPlaybackOk)) failures += "${fixture.name}: no se reproduce completo (${row.error})"
            if (fixture.expectError && row.playable) failures += "${fixture.name}: se esperaba error"
            if (fixture.expectTags && row.playable && row.artist.isBlank()) failures += "${fixture.name}: etiquetas no extraídas"
            if (row.playable && row.title != fixture.expectedTitle) failures += "${fixture.name}: título '${row.title}', se esperaba '${fixture.expectedTitle}'"
            // The Java ALAC decoder is only a fallback for ALAC: other formats keep their decoders,
            // and without a device ALAC decoder ALAC must go through it.
            val alac = fixture.name.startsWith("alac_")
            if (row.playable && !alac && row.decoder == AlacDecoder.NAME) failures += "${fixture.name}: decodificado por ${AlacDecoder.NAME}"
            if (row.playable && alac && !deviceDecodesAlac && row.decoder != AlacDecoder.NAME) {
                failures += "${fixture.name}: se esperaba ${AlacDecoder.NAME}, decodificador ${row.decoder}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun measure(fixture: Fixture, config: String, factory: (Context) -> ExoPlayer): Row {
        val file = File(dir, fixture.name)
        var decoder = "-"
        var decoderInits = 0
        val player = onMain {
            factory(context).apply {
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        decoder = decoderName
                        decoderInits++
                    }
                })
                // Same MediaItem shape as production (file-name title as fallback extra), with the URI set
                // as PlaybackService.onAddMediaItems does.
                val track = Track(
                    id = "fixture::${fixture.name}",
                    documentUri = Uri.fromFile(file).toString(),
                    title = fixture.name.substringBeforeLast('.'),
                    relativePath = fixture.name,
                    folder = FolderKey("fixture", "fixture"),
                )
                setMediaItem(track.toMediaItem().buildUpon().setUri(Uri.fromFile(file)).build())
                prepare()
            }
        }
        try {
            awaitUntil(10_000) { it.playbackState == Player.STATE_READY || it.playerError != null }.invoke(player)
            val error = onMain { player.playerError }
            if (error != null) {
                return Row(fixture.name, config, false, decoder, "${error.errorCodeName} (${error.kind()})",
                    null, fixture.expectedMs, false, "", "", "", 0, false, null, false)
            }
            val (duration, metadata, seekable) = onMain {
                Triple(player.duration.takeIf { it != C.TIME_UNSET }, player.mediaMetadata, player.isCurrentMediaItemSeekable)
            }

            var midSeekOk = false
            var endAfterSeek: Long? = null
            if (duration != null && seekable) {
                onMain { player.seekTo(duration / 2) }
                midSeekOk = awaitUntil(5_000) { it.playbackState == Player.STATE_READY && it.playerError == null }.invoke(player)
                onMain { player.seekTo(duration - 2_000); player.play() }
                val start = SystemClock.elapsedRealtime()
                if (awaitUntil(10_000) { it.playbackState == Player.STATE_ENDED || it.playerError != null }.invoke(player) &&
                    onMain { player.playerError } == null
                ) {
                    endAfterSeek = SystemClock.elapsedRealtime() - start
                }
            }

            onMain {
                player.pause()
                player.seekTo(0)
                player.playbackParameters = PlaybackParameters(8f)
                player.play()
            }
            val fullTimeout = (duration ?: 60_000) / 8 + 8_000
            val ended = awaitUntil(fullTimeout) { it.playbackState == Player.STATE_ENDED || it.playerError != null }.invoke(player)
            val fullOk = ended && onMain { player.playerError } == null

            return Row(
                fixture.name, config, true, decoder, "-", duration, fixture.expectedMs, seekable,
                metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
                metadata.albumTitle?.toString().orEmpty(), metadata.artworkData?.size ?: 0,
                midSeekOk, endAfterSeek, fullOk, decoderInits,
            )
        } finally {
            onMain { player.release() }
        }
    }

    private fun playerWith(context: Context, extractors: DefaultExtractorsFactory): ExoPlayer =
        ExoPlayer.Builder(context, DefaultMediaSourceFactory(context, extractors)).build()

    private fun awaitUntil(timeoutMs: Long, condition: (Player) -> Boolean): (Player) -> Boolean = { player ->
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var met = false
        while (!met && SystemClock.elapsedRealtime() < deadline) {
            met = onMain { condition(player) }
            if (!met) SystemClock.sleep(20)
        }
        met
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun copyAsset(name: String) {
        instrumentation.context.assets.open("formats/$name").use { input ->
            File(dir, name).outputStream().use { input.copyTo(it) }
        }
    }

    private fun logCodecs() {
        val mimes = listOf("audio/mpeg", "audio/flac", "audio/mp4a-latm", "audio/alac", "audio/vorbis", "audio/opus", "audio/raw")
        Log.i(TAG, "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        mimes.forEach { mime ->
            Log.i(TAG, "Decodificadores $mime: ${DeviceAudioEncoder.decoders(mime).ifEmpty { listOf("NINGUNO") }.joinToString()}")
        }
    }

    private fun logMatrix(rows: List<Row>) {
        Log.i(TAG, "| archivo | config | reproduce | decodificador | error | duración ms (esperada) | err % | seekable | seek 50% | fin tras seek ms (≈2000) | completa 8x | inits decodificador | título | artista | álbum | carátula bytes |")
        rows.forEach { r ->
            val err = r.durationErrorPct?.let { "%.1f".format(it) } ?: "-"
            Log.i(TAG, "| ${r.fixture} | ${r.config} | ${r.playable} | ${r.decoder} | ${r.error} | ${r.durationMs ?: "desconocida"} (${r.expectedMs ?: "-"}) | $err | ${r.seekable} | ${r.midSeekOk} | ${r.endAfterSeekMs ?: "-"} | ${r.fullPlaybackOk} | ${r.decoderInits} | ${r.title} | ${r.artist} | ${r.album} | ${r.artworkBytes} |")
        }
    }

    /** Whether Android itself has an ALAC decoder (then it takes ALAC and the Java decoder is not used). */
    private val deviceDecodesAlac get() = DeviceAudioEncoder.decoders("audio/alac").isNotEmpty()

    private companion object {
        const val TAG = "NezhFormatMatrix"
    }
}
