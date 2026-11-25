package com.hachicorp.nezh.playback

import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Pins how playback recovers from real player errors (ErrorRecovery in [PlaybackService]): a file
 * that fails is skipped while the user is playing, playback stops with a message when nothing
 * else can play, and a queue of broken files never loops, even with repeat on.
 *
 * A "watcher" controller stands in for an external controller (Bluetooth, lock screen).
 * Fixtures: assets/formats (corrupt.mp3, mp3_cbr_tagged.mp3); "missing" has no content.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackErrorRecoveryTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "err")

    private lateinit var connection: PlayerConnection
    private lateinit var watcher: MediaController
    /** UIs connected during a test (e.g. a recreated activity); disconnected in tearDown. */
    private val laterConnections = ArrayList<PlayerConnection>()

    private fun track(name: String): Track {
        val docId = "err/$name.mp3"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = name,
            relativePath = docId,
            folder = FolderKey(TestTreeProvider.AUTHORITY, "err"),
        )
    }

    private val corrupt1 = track("corrupt1")
    private val corrupt2 = track("corrupt2")
    private val corrupt3 = track("corrupt3")
    private val good = track("good")
    private val missing = track("missing") // listed by nobody: opening it fails with FileNotFound

    @Before
    fun setUp() {
        fun size(asset: String) = instrumentation.context.assets.open(asset).use { it.readBytes().size }
        val corruptSize = size("formats/corrupt.mp3")
        val spec = listOf(
            "err/corrupt1.mp3|$corruptSize|1|formats/corrupt.mp3",
            "err/corrupt2.mp3|$corruptSize|1|formats/corrupt.mp3",
            "err/corrupt3.mp3|$corruptSize|1|formats/corrupt.mp3",
            "err/good.mp3|${size("formats/mp3_cbr_tagged.mp3")}|1|formats/mp3_cbr_tagged.mp3",
        ).joinToString("\n")
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = onMain { PlayerConnection(context).also { it.connect() } }
        val future = onMain {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        watcher = future.get(10, TimeUnit.SECONDS)
        onMain {
            watcher.stop()
            watcher.clearMediaItems()
            watcher.repeatMode = Player.REPEAT_MODE_OFF
            watcher.shuffleModeEnabled = false
        }
        waitUntil("connected") { connection.state.value.connection == ConnectionStatus.CONNECTED }
    }

    @After
    fun tearDown() {
        onMain {
            watcher.stop()
            watcher.clearMediaItems()
            watcher.repeatMode = Player.REPEAT_MODE_OFF
            watcher.release()
            connection.disconnect()
            laterConnections.forEach { it.disconnect() }
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

    @Test
    fun aBrokenOrMissingFileIsSkippedWhilePlaying() {
        play(listOf(corrupt1, missing, good))
        waitUntil("the good file plays") {
            watcher.currentMediaItem?.mediaId == good.id && watcher.isPlaying && watcher.currentPosition > 300
        }
        val state = onMain { connection.state.value }
        assertEquals(2, state.skippedCount)
        val last = state.lastSkipped
        assertNotNull(last)
        assertEquals(PlaybackErrorKind.FILE, last!!.kind)
        assertEquals("missing", last.title)
        assertEquals(PlaybackErrorStage.READ_FILE, last.stage)
    }

    @Test
    fun withNothingElseToPlayPlaybackStopsWithTheReason() {
        play(listOf(good, corrupt1))
        waitUntil("the good file plays") { watcher.currentMediaItem?.mediaId == good.id && watcher.isPlaying }
        onMain { watcher.seekTo(1, 0) }
        waitUntil("stopped with a message") { connection.state.value.error != null }
        val error = onMain { connection.state.value.error }!!
        assertEquals(PlaybackErrorKind.FILE, error.kind)
        assertEquals("corrupt1", error.title)
        assertTrue("a Media3 error code is reported: ${error.code}", error.code.startsWith("ERROR_CODE_"))
        // The report and the player state reach controllers separately: wait for the latter.
        waitUntil("player stopped on the error", timeoutMs = 2_000) { !watcher.isPlaying && watcher.playerError != null }
    }

    /** A UI that connects now, as a recreated activity would; it asks the service for the error. */
    private fun connectAnotherUi(): PlayerConnection {
        val c = onMain { PlayerConnection(context).also { it.connect() } }
        laterConnections += c
        waitUntil("connected") { c.state.value.connection == ConnectionStatus.CONNECTED }
        return c
    }

    @Test
    fun aUiConnectingAfterAStopIsToldWhy() {
        play(listOf(good, corrupt1))
        waitUntil("the good file plays") { watcher.currentMediaItem?.mediaId == good.id && watcher.isPlaying }
        onMain { watcher.seekTo(1, 0) }
        waitUntil("player stopped on the error") { !watcher.isPlaying && watcher.playerError != null }
        val other = connectAnotherUi()
        waitUntil("the new UI shows the reason") { other.state.value.error?.title == "corrupt1" }
    }

    @Test
    fun afterRecoveringFromAStopANewUiShowsNoOldMessage() {
        play(listOf(good, corrupt1))
        waitUntil("the good file plays") { watcher.currentMediaItem?.mediaId == good.id && watcher.isPlaying }
        onMain { watcher.seekTo(1, 0) }
        waitUntil("player stopped on the error") { !watcher.isPlaying && watcher.playerError != null }
        // Retry on a playable item: the player leaves the error state.
        onMain { watcher.seekTo(0, 0); watcher.prepare(); watcher.play() }
        waitUntil("playing again") { watcher.playerError == null && watcher.isPlaying && watcher.currentPosition > 300 }
        val other = connectAnotherUi()
        SystemClock.sleep(1_000) // the error request is answered asynchronously
        assertEquals(null, onMain { other.state.value.error })
    }

    @Test
    fun aQueueOfBrokenFilesStopsInsteadOfLoopingEvenWithRepeatAll() {
        onMain { watcher.repeatMode = Player.REPEAT_MODE_ALL }
        play(listOf(corrupt1, corrupt2, corrupt3))
        waitUntil("stopped") { connection.state.value.error != null }
        SystemClock.sleep(1_500) // a loop would keep skipping and changing the item
        val state = onMain { connection.state.value }
        assertEquals("every other item tried once (queue size - 1)", 2, state.skippedCount)
        assertTrue("stopped on an error", onMain { watcher.playerError != null && !watcher.isPlaying })
    }

    /** Prepares [tracks] without playing: an error then stops playback instead of skipping. */
    private fun prepareWithoutPlaying(vararg tracks: Track) = onMain {
        watcher.setMediaItems(tracks.map { it.toMediaItem() })
        watcher.playWhenReady = false
        watcher.prepare()
    }

    /**
     * A3 (audit): after playback stopped on an error, "next" from an external controller (Bluetooth,
     * lock screen) moved to a playable track, but Nezh's UI kept showing the old message.
     * Reproduced on the Moto G7 Plus (2026-11-25) before the fix.
     */
    @Test
    fun externalNextAfterAStopClearsTheStaleMessage() {
        prepareWithoutPlaying(corrupt1, good)
        waitUntil("stopped on the broken file") { connection.state.value.error != null }
        // Not cleared early: nothing changed yet, the message stays.
        SystemClock.sleep(1_000)
        assertEquals("corrupt1", onMain { connection.state.value.error?.title })

        onMain { watcher.seekToNextMediaItem(); watcher.prepare() }
        waitUntil("the good file is current and ready") {
            watcher.currentMediaItem?.mediaId == good.id && watcher.playbackState == Player.STATE_READY
        }
        SystemClock.sleep(500)
        assertEquals("message about the previous file", null, onMain { connection.state.value.error })
    }

    @Test
    fun aNewErrorAfterExternalNextReplacesTheOldMessage() {
        prepareWithoutPlaying(corrupt1, corrupt2, good)
        waitUntil("stopped on corrupt1") { connection.state.value.error?.title == "corrupt1" }
        onMain { watcher.seekToNextMediaItem(); watcher.prepare() }
        waitUntil("stopped on corrupt2") { connection.state.value.error?.title == "corrupt2" }
        SystemClock.sleep(500)
        assertEquals("the new error stays", "corrupt2", onMain { connection.state.value.error?.title })
    }
}
