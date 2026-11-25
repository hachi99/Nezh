package com.hachicorp.nezh.playback

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.LyricsReader
import com.hachicorp.nezh.library.TestTreeProvider
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackIdentity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Now Playing shows for the track that is actually playing, read the way the screen reads it:
 * the current document from the player, its embedded lyrics (MP3 USLT, FLAC LYRICS, M4A ©lyr, and a
 * file without lyrics), and the audio format the player reports for the selected stream.
 *
 * Fixtures: assets/lyrics (tools/fixtures/make_lyrics_fixtures.py), served by the test provider.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingDataTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "lyr")

    /** asset -> expected lyrics (null when the file has none). */
    private val cases: List<Pair<String, String?>> = instrumentation.context.assets.open("lyrics/manifest.tsv")
        .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.map { line ->
            val (asset, lyrics) = line.split('\t')
            asset to lyrics.takeIf { it != "-" }?.replace("\\n", "\n")
        }

    private lateinit var connection: PlayerConnection

    private fun track(asset: String): Track {
        val docId = "lyr/$asset"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = asset,
            relativePath = docId,
            folder = FolderKey(TestTreeProvider.AUTHORITY, "lyr"),
        )
    }

    @Before
    fun setUp() {
        val spec = cases.joinToString("\n") { (asset, _) ->
            val size = instrumentation.context.assets.open("lyrics/$asset").use { it.readBytes().size }
            "lyr/$asset|$size|1|lyrics/$asset"
        }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = onMain { PlayerConnection(context).also { it.connect() } }
        waitUntil("connected") { connection.state.value.connection == ConnectionStatus.CONNECTED }
    }

    @After
    fun tearDown() {
        if (::connection.isInitialized) onMain {
            if (!connection.state.value.showPlayButton) connection.playPause()
            connection.disconnect()
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

    @Test
    fun lyricsAndStreamFormatOfThePlayingTrack() {
        val expectedCodec = mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4a-latm")
        val reader = LyricsReader(context)
        for ((asset, lyrics) in cases) {
            val track = track(asset)
            onMain { MainScope().launch { connection.playTracks(listOf(track), 0) } }
            waitUntil("$asset playing") {
                connection.state.value.currentMediaId == track.id && connection.positionMs() > 300 && connection.currentAudioFormat() != null
            }
            val uri = onMain { connection.currentDocumentUri() }
            assertEquals("$asset: document of the current item", track.documentUri, uri.toString())
            // Off the main thread, as the screen does.
            assertEquals("$asset: embedded lyrics", lyrics, reader.read(uri!!))
            val format = onMain { connection.currentAudioFormat() }!!
            assertEquals("$asset: codec reported by the player", expectedCodec[asset.substringAfterLast('.')], format.sampleMimeType)
            val info = technicalInfo(format, track.relativePath, null, onMain { connection.durationMs() })
            val stream = info.stream.toMap()
            assertEquals("$asset: sample rate", "44,1 kHz", stream["Frecuencia de muestreo"])
            assertEquals("$asset: file format", asset.substringAfterLast('.').uppercase(), info.file.toMap()["Formato del archivo"])
            android.util.Log.i("NezhNowPlaying", "$asset: stream=$stream file=${info.file}")
        }
    }
}
