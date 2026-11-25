package com.hachicorp.nezh.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.QueueFilter
import com.hachicorp.nezh.library.TestTreeProvider
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackIdentity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Queue operations through the real path: UI [PlayerConnection] → MediaController → the real
 * [PlaybackService] (ExoPlayer + MediaSession), playing real MP3 files served by a SAF-style
 * provider. A second controller watches the player for anything that would interrupt playback:
 * a track change, a position jump or a pause.
 */
@RunWith(AndroidJUnit4::class)
class QueueOperationsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "q")
    private val tracks = (1..5).map { track("t$it") }

    private lateinit var connection: PlayerConnection
    private lateinit var watcher: MediaController
    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val watch = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events += "transition:${mediaItem?.mediaId}:$reason"
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            events += "discontinuity:$reason"
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) events += "paused"
        }
    }

    private fun track(name: String): Track {
        val docId = "q/$name.mp3"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = name,
            relativePath = "q/$name.mp3",
            folder = FolderKey(TestTreeProvider.AUTHORITY, "q"),
        )
    }

    @Before
    fun setUp() {
        // ~20 s tagged MP3 per entry (in-memory provider, nothing written to storage).
        val spec = (1..LARGE_QUEUE).joinToString("\n") { "q/t$it.mp3|321481|1|formats/mp3_cbr_tagged.mp3" }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = onMain { PlayerConnection(context).also { it.connect() } }
        val future = onMain {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        watcher = future.get(10, TimeUnit.SECONDS)
        onMain {
            watcher.stop()
            watcher.clearMediaItems()
            watcher.addListener(watch)
        }
        waitUntil("connected") { connection.state.value.connection == ConnectionStatus.CONNECTED }
    }

    @After
    fun tearDown() {
        onMain {
            watcher.removeListener(watch)
            watcher.stop()
            watcher.clearMediaItems()
            watcher.release()
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

    /** Player queue ids; call on the main thread (e.g. inside [waitUntil]). */
    private fun playerIds(): List<String> = (0 until watcher.mediaItemCount).map { watcher.getMediaItemAt(it).mediaId }

    private fun queueIds(): List<String> = onMain { playerIds() }

    private fun uiQueueIds(): List<String> {
        val queue = connection.state.value.queue
        return (0 until queue.size).map { queue[it].id }
    }

    private fun ids(vararg names: String) = names.map { track(it).id }

    /** Plays [list] from [start] and waits until it is really playing (position advancing). */
    private fun playAndSettle(list: List<Track>, start: Int) {
        onMain { runBlocking { connection.playTracks(list, start) } }
        waitUntil("playing") { watcher.isPlaying && watcher.currentPosition > 500 }
        events.clear()
    }

    /** Asserts the same track kept playing, without pauses, jumps or track changes. */
    private fun assertUninterrupted(mediaId: String, positionBefore: Long) {
        SystemClock.sleep(600)
        onMain {
            assertEquals(mediaId, watcher.currentMediaItem?.mediaId)
            assertTrue("still playing", watcher.isPlaying)
            assertTrue("position kept and advancing", watcher.currentPosition > positionBefore)
        }
        assertEquals("no interruption events", emptyList<String>(), events.toList())
    }

    private fun snapshot() = connection.state.value.queue

    @Test
    fun loadingAQueueOfThousandsOfTracksDoesNotBlockTheMainThread() {
        val library = (1..LARGE_QUEUE).map { track("t$it") }
        // A main-thread heartbeat: the longest gap between beats is how long the UI was blocked.
        val handler = Handler(Looper.getMainLooper())
        var last = SystemClock.uptimeMillis()
        var longestGap = 0L
        var beating = true
        val beat = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                longestGap = maxOf(longestGap, now - last)
                last = now
                if (beating) handler.postDelayed(this, 16)
            }
        }
        onMain {
            last = SystemClock.uptimeMillis()
            handler.post(beat)
        }
        // As the app does: launched on the main thread, which stays free while the items are built.
        onMain { MainScope().launch { connection.playTracks(library, 1234) } }
        waitUntil("playing the selected track of the large queue") {
            watcher.isPlaying && watcher.currentMediaItem?.mediaId == library[1234].id
        }
        SystemClock.sleep(500)
        onMain { beating = false }
        assertEquals(library.map { it.id }, queueIds())
        assertEquals(LARGE_QUEUE, connection.state.value.queue.size)
        android.util.Log.i("NezhPerf", "largeQueue mainThreadLongestGapMs=$longestGap")
        assertTrue("main thread blocked for $longestGap ms", longestGap < MAX_MAIN_THREAD_STALL_MS)
    }

    @Test
    fun transportControlsWorkAcrossThreads() {
        playAndSettle(tracks.take(3), 1)
        // Seek within the track (the session and player run on their own thread).
        onMain { watcher.seekTo(10_000) }
        waitUntil("seeked") { watcher.currentPosition in 10_000..13_000 }
        assertEquals(tracks[1].id, onMain { watcher.currentMediaItem?.mediaId })
        // Pause and resume keep the position.
        onMain { connection.playPause() }
        waitUntil("paused") { !watcher.isPlaying }
        val pausedAt = onMain { watcher.currentPosition }
        SystemClock.sleep(500)
        assertTrue(onMain { watcher.currentPosition } - pausedAt < 100)
        onMain { connection.playPause() }
        waitUntil("resumed") { watcher.isPlaying && watcher.currentPosition > pausedAt }
        // Next and previous.
        onMain { connection.next() }
        waitUntil("next") { watcher.currentMediaItem?.mediaId == tracks[2].id }
        onMain { watcher.seekToPreviousMediaItem() }
        waitUntil("previous") { watcher.currentMediaItem?.mediaId == tracks[1].id }
        assertEquals(tracks.take(3).map { it.id }, queueIds())
    }

    @Test
    fun tappingATrackReplacesTheQueueWithTheWholeLibraryInOrder() {
        playAndSettle(tracks, 2)
        assertEquals(tracks.map { it.id }, queueIds())
        onMain { assertEquals(2, watcher.currentMediaItemIndex) }
        assertEquals(2, connection.state.value.currentIndex)
    }

    @Test
    fun addingToTheQueueAppendsDuplicatesWithoutInterrupting() {
        playAndSettle(tracks.take(3), 1)
        val position = onMain { watcher.currentPosition }
        onMain {
            assertTrue(connection.addToQueue(tracks[4]))
            assertTrue(connection.addToQueue(tracks[4])) // duplicates are allowed
            assertTrue(connection.addToQueue(tracks[0]))
        }
        waitUntil("added") { watcher.mediaItemCount == 6 }
        assertEquals(ids("t1", "t2", "t3", "t5", "t5", "t1"), queueIds())
        onMain { assertEquals(1, watcher.currentMediaItemIndex) }
        assertUninterrupted(tracks[1].id, position)
        assertEquals(queueIds(), uiQueueIds())
    }

    @Test
    fun addingToAnEmptyQueueStartsPlayingTheTrack() {
        onMain { assertTrue(connection.addToQueue(tracks[3])) }
        waitUntil("playing") { watcher.isPlaying }
        assertEquals(ids("t4"), queueIds())
    }

    @Test
    fun addingAfterTheQueueEndedPlaysTheNewTrack() {
        playAndSettle(tracks.take(1), 0)
        onMain { watcher.seekTo(watcher.duration - 300) }
        waitUntil("ended") { watcher.playbackState == Player.STATE_ENDED }
        onMain { assertTrue(connection.addToQueue(tracks[1])) }
        waitUntil("new track playing") { watcher.isPlaying && watcher.currentMediaItem?.mediaId == tracks[1].id }
        assertEquals(ids("t1", "t2"), queueIds())
    }

    @Test
    fun removingUpcomingOrPreviousItemsKeepsTheCurrentTrack() {
        playAndSettle(tracks, 2)
        val position = onMain { watcher.currentPosition }
        onMain { assertTrue(connection.removeFromQueue(snapshot(), 4)) } // upcoming
        waitUntil("removed upcoming") { watcher.mediaItemCount == 4 }
        onMain { assertTrue(connection.removeFromQueue(snapshot(), 0)) } // previous
        waitUntil("removed previous") { watcher.mediaItemCount == 3 }
        assertEquals(ids("t2", "t3", "t4"), queueIds())
        onMain { assertEquals(1, watcher.currentMediaItemIndex) }
        assertUninterrupted(tracks[2].id, position)
    }

    @Test
    fun theCurrentItemCannotBeRemovedOrMoved() {
        playAndSettle(tracks, 2)
        onMain {
            assertFalse(connection.removeFromQueue(snapshot(), 2))
            assertFalse(connection.moveInQueue(snapshot(), 2, 4))
            assertFalse(connection.moveInQueue(snapshot(), 3, 2)) // not before or onto the current
            assertFalse(connection.moveInQueue(snapshot(), 4, 0))
            assertFalse(connection.moveInQueue(snapshot(), 0, 4)) // previous items are not reordered
        }
        assertEquals(tracks.map { it.id }, queueIds())
    }

    @Test
    fun reorderingUpcomingItemsDoesNotInterrupt() {
        playAndSettle(tracks, 1)
        val position = onMain { watcher.currentPosition }
        onMain { assertTrue(connection.moveInQueue(snapshot(), 4, 2)) }
        waitUntil("moved") { playerIds() == ids("t1", "t2", "t5", "t3", "t4") }
        onMain { assertTrue(connection.moveInQueue(snapshot(), 2, 4)) }
        waitUntil("moved back") { playerIds() == tracks.map { it.id } }
        assertUninterrupted(tracks[1].id, position)
        // The reordered queue is what plays next.
        onMain { assertTrue(connection.moveInQueue(snapshot(), 4, 2)) }
        waitUntil("moved again") { playerIds() == ids("t1", "t2", "t5", "t3", "t4") }
        onMain { connection.next() }
        waitUntil("next is the moved track") { watcher.currentMediaItem?.mediaId == tracks[4].id }
    }

    @Test
    fun clearingUpcomingKeepsTheCurrentTrackPlaying() {
        playAndSettle(tracks, 1)
        val position = onMain { watcher.currentPosition }
        onMain { assertTrue(connection.clearUpcoming(snapshot())) }
        waitUntil("cleared") { watcher.mediaItemCount == 2 }
        assertEquals(ids("t1", "t2"), queueIds())
        assertUninterrupted(tracks[1].id, position)
        onMain { assertFalse("nothing left to clear", connection.clearUpcoming(snapshot())) }
    }

    @Test
    fun editsMadeOnAnOutdatedQueueAreIgnored() {
        playAndSettle(tracks.take(3), 0)
        val old = snapshot()
        onMain { connection.addToQueue(tracks[3]) }
        waitUntil("added") { watcher.mediaItemCount == 4 && snapshot() !== old }
        onMain {
            assertFalse(connection.removeFromQueue(old, 1))
            assertFalse(connection.moveInQueue(old, 1, 2))
            assertFalse(connection.clearUpcoming(old))
        }
        assertEquals(ids("t1", "t2", "t3", "t4"), queueIds())
    }

    // --- Pruning the queue to the library (MainActivity.keepQueueWithinLibrary) -----------------

    private val folder = FolderKey(TestTreeProvider.AUTHORITY, "q")

    @Test
    fun pruningRemovesTracksNoLongerInTheLibraryAndKeepsTheCurrentOnePlaying() {
        playAndSettle(tracks, 2) // t3
        val position = onMain { watcher.currentPosition }
        // t2 and t4 were removed from the library; the others are still indexed.
        onMain { connection.removeItemsNotAllowed(QueueFilter(setOf(folder), setOf(folder), ids("t1", "t3", "t5").toSet())) }
        waitUntil("pruned") { playerIds() == ids("t1", "t3", "t5") }
        assertEquals(ids("t1", "t3", "t5"), onMain { uiQueueIds() })
        assertUninterrupted(track("t3").id, position)
    }

    @Test
    fun pruningAFolderThatLostPermissionEmptiesTheQueue() {
        playAndSettle(tracks, 0)
        onMain { connection.removeItemsNotAllowed(QueueFilter(emptySet(), emptySet(), emptySet())) }
        waitUntil("queue emptied") { watcher.mediaItemCount == 0 }
        waitUntil("UI queue emptied") { connection.state.value.queue.size == 0 }
    }

    @Test
    fun theQueueKeepsWorkingAfterTheTrackChanges() {
        playAndSettle(tracks.take(3), 0)
        onMain { connection.next() }
        waitUntil("second track") { watcher.isPlaying && watcher.currentMediaItemIndex == 1 && watcher.currentPosition > 300 }
        events.clear()
        val position = onMain { watcher.currentPosition }
        onMain { assertTrue(connection.addToQueue(tracks[4])) }
        waitUntil("added") { watcher.mediaItemCount == 4 }
        onMain { assertTrue(connection.moveInQueue(snapshot(), 3, 2)) }
        waitUntil("moved") { playerIds() == ids("t1", "t2", "t5", "t3") }
        onMain { assertTrue(connection.removeFromQueue(snapshot(), 0)) }
        waitUntil("removed") { watcher.mediaItemCount == 3 }
        assertEquals(ids("t2", "t5", "t3"), queueIds())
        assertUninterrupted(tracks[1].id, position)
        assertEquals(0, connection.state.value.currentIndex)
    }

    private companion object {
        /** About the size of the library this was measured with (2,667 tracks on a Moto G7 Plus). */
        const val LARGE_QUEUE = 3000
        /**
         * Before the player moved off the main thread, loading this queue blocked the main thread
         * for 2.3 to 5.8 s on the emulator (debug build). The controller side alone takes ~20-80 ms.
         */
        const val MAX_MAIN_THREAD_STALL_MS = 300L
    }
}
