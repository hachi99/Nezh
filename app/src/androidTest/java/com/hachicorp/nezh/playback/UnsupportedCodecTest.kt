package com.hachicorp.nezh.playback

import android.media.MediaCodecList
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.TestTreeProvider
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackIdentity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file whose audio no decoder can play must be reported and skipped, or stop playback with a
 * message: never "play" in silence. Also checks that WAV and MP3 next to it play with a real audio
 * track.
 *
 * The example is ALAC with 20-bit samples: Nezh's Java ALAC decoder handles 16 and 24 bit only (16
 * and 24-bit ALAC now play on every device, see FormatCompatibilityTest), and the test runs only on
 * devices without an ALAC decoder of their own. It also checks that ALAC data the Java decoder
 * cannot decode (an unsupported prediction type) is reported as a decoding error and skipped.
 *
 * Fixtures: assets/codecs (tools/fixtures/make_codec_fixtures.py) and the ALAC variants of
 * tools/fixtures/make_alac_variant_fixtures.py. Skipped on devices that have an ALAC decoder.
 */
@RunWith(AndroidJUnit4::class)
class UnsupportedCodecTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "codec")
    /** Document name -> test asset. */
    private val assets = mapOf(
        "tone_alac_20bit.m4a" to "codecs/tone_alac_20bit.m4a",
        "alac_bad_prediction.m4a" to "formats/alac_bad_prediction.m4a",
        "tone_pcm.wav" to "codecs/tone_pcm.wav",
        "tone_mp3.mp3" to "codecs/tone_mp3.mp3",
    )
    private val connections = ArrayList<PlayerConnection>()
    private lateinit var connection: PlayerConnection

    private fun track(asset: String): Track {
        val docId = "codec/$asset"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = asset,
            relativePath = docId,
            folder = FolderKey(TestTreeProvider.AUTHORITY, "codec"),
        )
    }

    private val alac = track("tone_alac_20bit.m4a")
    private val badAlac = track("alac_bad_prediction.m4a")
    private val wav = track("tone_pcm.wav")
    private val mp3 = track("tone_mp3.mp3")

    @Before
    fun setUp() {
        val hasAlacDecoder = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("audio/alac", ignoreCase = true) }
        }
        assumeFalse("This device can decode ALAC", hasAlacDecoder)
        val spec = assets.entries.joinToString("\n") { (name, asset) ->
            val size = instrumentation.context.assets.open(asset).use { it.readBytes().size }
            "codec/$name|$size|1|$asset"
        }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = connect()
    }

    private fun connect(): PlayerConnection {
        val c = onMain { PlayerConnection(context).also { it.connect() } }
        connections += c
        waitUntil("connected") { c.state.value.connection == ConnectionStatus.CONNECTED }
        return c
    }

    @After
    fun tearDown() {
        onMain {
            connections.firstOrNull()?.let { if (!it.state.value.showPlayButton) it.playPause() }
            connections.forEach { it.disconnect() }
        }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, "", null)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!onMain(condition)) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out: $message")
            SystemClock.sleep(50)
        }
    }

    private fun play(tracks: List<Track>) {
        onMain { MainScope().launch { connection.playTracks(tracks, 0) } }
    }

    /** True when the player has selected an audio track of [track] and its position advances. Main thread. */
    private fun audiblyPlaying(track: Track): Boolean =
        connection.state.value.currentMediaId == track.id && !connection.state.value.showPlayButton &&
            connection.currentAudioFormat() != null && connection.positionMs() > 300

    @Test
    fun unsupportedFilesAreSkippedAndTheNextPlayableFilePlays() {
        play(listOf(alac, alac, wav, mp3))
        waitUntil("the WAV after two ALAC files plays") { audiblyPlaying(wav) }
        val state = onMain { connection.state.value }
        assertEquals("both ALAC files skipped", 2, state.skippedCount)
        val report = state.lastSkipped
        assertNotNull(report)
        report!!
        assertEquals(PlaybackErrorStage.DECODER, report.stage)
        assertTrue("reason names the codec: ${report.code}", report.code.endsWith("audio/alac"))
        assertEquals(C.ENCODING_PCM_16BIT, onMain { connection.currentAudioFormat() }!!.pcmEncoding)
        // MP3 after WAV: the normal transition still works.
        onMain { connection.next() }
        waitUntil("the MP3 plays") { audiblyPlaying(mp3) }
    }

    @Test
    fun withNothingPlayableAfterItPlaybackStopsWithAMessageInsteadOfRunningSilently() {
        play(listOf(alac))
        waitUntil("stopped with a message") { connection.state.value.error != null }
        val report = onMain { connection.state.value.error }!!
        assertEquals(PlaybackErrorKind.FILE, report.kind)
        assertEquals(PlaybackErrorStage.DECODER, report.stage)
        assertTrue(report.code.endsWith("audio/alac"))
        assertPausedAndNotAdvancing()

        // A UI that connects now (e.g. the activity recreated) is told why playback stopped.
        val other = connect()
        waitUntil("reconnected UI gets the report") { other.state.value.error != null }

        // Pressing play again must not start a silent clock either.
        onMain { connection.playPause() }
        SystemClock.sleep(1_500)
        waitUntil("reported again") { connection.state.value.error != null }
        assertPausedAndNotAdvancing()
    }

    @Test
    fun afterAStopOnAnUnsupportedFileAndPlayingAnotherANewUiShowsNoOldMessage() {
        play(listOf(alac))
        waitUntil("stopped with a message") { connection.state.value.error != null }
        play(listOf(wav))
        waitUntil("the WAV plays") { audiblyPlaying(wav) }
        val other = connect()
        SystemClock.sleep(1_000) // the error request is answered asynchronously
        assertEquals(null, onMain { other.state.value.error })
    }

    @Test
    fun alacDataTheDecoderCannotDecodeIsReportedAsADecodingErrorAndSkipped() {
        play(listOf(badAlac, wav))
        waitUntil("the WAV after the damaged ALAC file plays", timeoutMs = 20_000) { audiblyPlaying(wav) }
        val report = onMain { connection.state.value.lastSkipped }
        assertNotNull("the ALAC file was reported", report)
        report!!
        assertEquals(badAlac.id, report.mediaId)
        assertEquals(PlaybackErrorStage.DECODER, report.stage)
        assertEquals("ERROR_CODE_DECODING_FAILED", report.code)
    }

    private fun assertPausedAndNotAdvancing() {
        // The report and the pause reach the controller separately; the pause follows within ms.
        waitUntil("paused", timeoutMs = 2_000) { connection.state.value.showPlayButton }
        val first = onMain { connection.positionMs() }
        SystemClock.sleep(1_000)
        val second = onMain { connection.positionMs() }
        assertEquals("position must not advance without audio", first, second)
    }
}
