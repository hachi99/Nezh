package com.hachicorp.nezh.playback

import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FakePermissions
import com.hachicorp.nezh.library.PlatformMetadataReader
import com.hachicorp.nezh.library.TestLibrary
import com.hachicorp.nezh.library.TestTreeProvider
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackIdentity
import com.hachicorp.nezh.library.db.MetadataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real playback of tracks whose title / artist / album use many scripts (Japanese, Chinese, Korean,
 * Cyrillic, Arabic, Greek, diacritics in composed and decomposed form, emoji, combining marks),
 * in MP3 (ID3v2.3 UTF-16, ID3v2.4 UTF-8, ID3v2.4 UTF-16BE, Shift-JIS mislabelled as Latin-1),
 * FLAC (Vorbis comments) and M4A (iTunes atoms), with Unicode folder and file names.
 *
 * Fixtures: assets/unicode (tools/fixtures/make_unicode_fixtures.py). Each file goes through the
 * production path: SAF-style provider → index with the platform metadata reader → Track →
 * MediaItem → PlayerConnection → MediaController → PlaybackService (its own thread) → ExoPlayer,
 * and must actually play (position advancing). Failures are reported by stage.
 */
@RunWith(AndroidJUnit4::class)
class UnicodePlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private data class Case(
        val asset: String, val path: String, val title: String, val artist: String, val album: String,
        val exact: Boolean, val cover: Boolean,
    )

    private val cases: List<Case> = instrumentation.context.assets.open("unicode/manifest.tsv").bufferedReader(Charsets.UTF_8)
        .readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            Case(f[0], f[1], f[2], f[3], f[4], exact = f[5] == "exact", cover = f[6] == "cover")
        }

    private val stack = TestLibrary(context, FakePermissions(), metadata = PlatformMetadataReader(context))
    private lateinit var connection: PlayerConnection
    private lateinit var watcher: MediaController

    @Before
    fun setUp() {
        val spec = cases.joinToString("\n") { c ->
            val size = instrumentation.context.assets.open("unicode/${c.asset}").use { it.readBytes().size }
            "${c.path}|$size|1|unicode/${c.asset}"
        }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = onMain { PlayerConnection(context).also { it.connect() } }
        val future = onMain {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        watcher = future.get(10, TimeUnit.SECONDS)
        waitUntil("connected") { connection.state.value.connection == ConnectionStatus.CONNECTED }
    }

    @After
    fun tearDown() {
        onMain {
            if (::watcher.isInitialized) {
                watcher.stop()
                watcher.clearMediaItems()
                watcher.release()
            }
            if (::connection.isInitialized) connection.disconnect()
        }
        stack.close()
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, "", null)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!onMain(condition)) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out: $message")
            SystemClock.sleep(50)
        }
    }

    private fun indexAll(): List<Track> {
        runBlocking(Dispatchers.Main) {
            stack.library.load()
            stack.library.addFolder(DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "uni"))
        }
        val ids = cases.map { TrackIdentity.of(TestTreeProvider.AUTHORITY, it.path) }
        // Off the main thread: the condition reads the database.
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!run {
            val tracks = stack.db.trackDao().getTracks(ids)
            tracks.size == ids.size && tracks.none { it.metadataState == MetadataState.PENDING } &&
                !stack.library.state.value.isScanning && stack.library.state.value.tracks.size == ids.size
        }) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out indexing ${cases.size} files")
            SystemClock.sleep(100)
        }
        return stack.library.state.value.tracks
    }

    private fun codePoints(s: String?) = s?.codePoints()?.toArray()?.joinToString(" ") { "U+%04X".format(it) }

    /** Plays [track] alone; returns null if it played (position advancing), else the failure. */
    private fun play(track: Track): String? {
        onMain { MainScope().launch { connection.playTracks(listOf(track), 0) } }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var started = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val (error, playing) = onMain {
                watcher.playerError to (watcher.isPlaying && watcher.currentMediaItem?.mediaId == track.id && watcher.currentPosition > 400)
            }
            if (playing) started = true
            if (error != null) return stageOf(error, started)
            if (started) return null
            SystemClock.sleep(50)
        }
        return "no empezó a sonar en 10 s (sin error del reproductor)"
    }

    private fun stageOf(error: PlaybackException, started: Boolean): String {
        val stage = when {
            started -> "5 reproducción ya iniciada"
            error.errorCode in 2000..2999 -> "4 abrir/leer el archivo"
            error.errorCode in 3000..3999 -> "3 preparar: contenedor/extractor"
            error.errorCode in 4000..4999 -> "3 preparar: decoder"
            else -> "otra"
        }
        return "etapa $stage: ${error.errorCodeName} (${error.cause?.javaClass?.simpleName}: ${error.cause?.message})"
    }

    @Test
    fun everyUnicodeCombinationIsIndexedWithItsOwnTextAndActuallyPlays() {
        val tracks = indexAll().associateBy { it.id }
        val failures = ArrayList<String>()
        for (case in cases) {
            val id = TrackIdentity.of(TestTreeProvider.AUTHORITY, case.path)
            val label = "${case.asset} [${case.path}]"
            // 1. Discovery and metadata.
            val entity = stack.db.trackDao().getTrack(id)
            val track = tracks[id]
            if (entity == null || track == null) { failures += "$label: etapa 1 descubrimiento: no está en la biblioteca"; continue }
            if (entity.metadataState != MetadataState.READ) failures += "$label: etapa 1 metadatos: no se pudieron leer (estado ${entity.metadataState})"
            if (case.exact) {
                for ((field, expected, actual) in listOf(
                    Triple("título", case.title, track.title), Triple("artista", case.artist, track.artist), Triple("álbum", case.album, track.album),
                )) {
                    if (expected != actual) failures += "$label: etapa 1 metadatos: $field «$actual» (${codePoints(actual)}) ≠ «$expected» (${codePoints(expected)})"
                }
            }
            if (!case.exact) {
                android.util.Log.i("NezhUnicode", "${case.asset}: lectura no estándar: título «${track.title}» artista «${track.artist}» álbum «${track.album}»")
            }
            if (case.cover) {
                val cover = track.artworkUri?.let { Uri.parse(it).path?.let(::File) }
                if (cover == null || !cover.isFile) failures += "$label: carátula: no disponible"
            }
            // 2. Playback item: identity and location come from the document, never from tags.
            val item = track.toMediaItem()
            if (item.mediaId != id) failures += "$label: etapa 2 elemento: mediaId ${item.mediaId}"
            if (item.requestMetadata.mediaUri.toString() != track.documentUri) failures += "$label: etapa 2 elemento: URI distinta"
            // 3-5. Real playback through the session.
            play(track)?.let { failures += "$label: $it" } ?: run {
                if (case.exact) {
                    val (title, artist, album) = onMain {
                        Triple(watcher.mediaMetadata.title?.toString(), watcher.mediaMetadata.artist?.toString(), watcher.mediaMetadata.albumTitle?.toString())
                    }
                    if (title != case.title) failures += "$label: reproductor muestra título «$title»"
                    if (artist != case.artist) failures += "$label: reproductor muestra artista «$artist»"
                    if (album != case.album) failures += "$label: reproductor muestra álbum «$album»"
                }
            }
        }
        android.util.Log.i("NezhUnicode", "cases=${cases.size} failures=${failures.size}")
        failures.forEach { android.util.Log.w("NezhUnicode", it) }
        assertTrue("${failures.size} fallos de ${cases.size} archivos:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun unicodeTracksWorkInTheQueueWithTransportControls() {
        val tracks = indexAll()
        val picked = listOf("en_ja_artist_id3v24_utf8.mp3", "ar_ar_vorbis.flac", "ko_ko_ilst.m4a", "symbols_id3v23_utf16.mp3")
            .map { asset -> tracks.first { it.id == TrackIdentity.of(TestTreeProvider.AUTHORITY, cases.first { c -> c.asset == asset }.path) } }
        onMain { MainScope().launch { connection.playTracks(picked.take(3), 0) } }
        waitUntil("playing the first one") { watcher.isPlaying && watcher.currentMediaItem?.mediaId == picked[0].id && watcher.currentPosition > 300 }
        onMain { assertTrue(connection.addToQueue(picked[3])) }
        waitUntil("added") { watcher.mediaItemCount == 4 }
        assertEquals(picked[0].id, onMain { watcher.currentMediaItem?.mediaId })
        onMain { connection.next() }
        waitUntil("next (FLAC, Arabic)") { watcher.isPlaying && watcher.currentMediaItem?.mediaId == picked[1].id }
        onMain { watcher.seekTo(1_500) }
        waitUntil("seek") { watcher.currentPosition in 1_500..3_000 }
        onMain { connection.playPause() }
        waitUntil("paused") { !watcher.isPlaying && watcher.playbackState == Player.STATE_READY }
        onMain { connection.playPause() }
        waitUntil("resumed") { watcher.isPlaying }
        onMain { connection.previous() } // past 3 s? no: restarts or goes back; either way it must keep playing a valid item
        waitUntil("previous") { watcher.isPlaying && watcher.currentMediaItem?.mediaId in picked.map { it.id } }
        onMain { watcher.seekToDefaultPosition(3) }
        waitUntil("the added track plays") { watcher.isPlaying && watcher.currentMediaItem?.mediaId == picked[3].id && watcher.currentPosition > 300 }
        assertEquals(null, onMain { watcher.playerError })
    }
}
