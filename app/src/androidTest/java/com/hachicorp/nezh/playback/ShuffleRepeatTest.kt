package com.hachicorp.nezh.playback

import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Shuffle and repeat through the real path (PlayerConnection → MediaController → PlaybackService
 * on its own thread → ExoPlayer with NezhShuffleOrder), checking what actually plays, not only the
 * button state. A second controller watches for interruptions (track change, position jump, pause).
 */
@RunWith(AndroidJUnit4::class)
class ShuffleRepeatTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "s")
    private val tracks = (1..8).map { track("t$it") }

    private lateinit var connection: PlayerConnection
    private lateinit var watcher: MediaController
    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val watch = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { events += "transition:$reason" }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { events += "discontinuity:$reason" }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) events += "paused" }
    }

    private fun track(name: String): Track {
        val docId = "s/$name.mp3"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = name, relativePath = docId, folder = FolderKey(TestTreeProvider.AUTHORITY, "s"),
        )
    }

    @Before
    fun setUp() {
        val spec = (1..8).joinToString("\n") { "s/t$it.mp3|321481|1|formats/mp3_cbr_tagged.mp3" }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
        connection = onMain { PlayerConnection(context).also { it.connect() } }
        val future = onMain {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        watcher = future.get(10, TimeUnit.SECONDS)
        onMain {
            watcher.stop()
            watcher.clearMediaItems()
            watcher.shuffleModeEnabled = false
            watcher.repeatMode = Player.REPEAT_MODE_OFF
            watcher.addListener(watch)
        }
        waitUntil("connected") { connection.state.value.connection == ConnectionStatus.CONNECTED }
    }

    @After
    fun tearDown() {
        onMain {
            if (::watcher.isInitialized) {
                watcher.removeListener(watch)
                watcher.stop()
                watcher.clearMediaItems()
                watcher.shuffleModeEnabled = false
                watcher.repeatMode = Player.REPEAT_MODE_OFF
                watcher.release()
            }
            if (::connection.isInitialized) connection.disconnect()
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
            SystemClock.sleep(30)
        }
    }

    private fun playAndSettle(list: List<Track>, start: Int) {
        onMain { MainScope().launch { connection.playTracks(list, start) } }
        waitUntil("playing") { watcher.isPlaying && watcher.currentMediaItemIndex == start && watcher.currentPosition > 500 }
        events.clear()
    }

    private fun index() = onMain { watcher.currentMediaItemIndex }

    /** Goes to the next track (user "next") and returns its queue index once it plays. */
    private fun next(): Int {
        val before = index()
        onMain { connection.next() }
        waitUntil("next from $before") { watcher.currentMediaItemIndex != before && watcher.isPlaying }
        return index()
    }

    private fun setShuffle(on: Boolean) {
        if (onMain { watcher.shuffleModeEnabled } != on) onMain { connection.toggleShuffle() }
        waitUntil("shuffle $on in the UI state") { connection.state.value.shuffleEnabled == on && watcher.shuffleModeEnabled == on }
    }

    private fun setRepeat(mode: Int) {
        var guard = 0
        while (onMain { watcher.repeatMode } != mode && guard++ < 3) {
            val before = onMain { watcher.repeatMode }
            onMain { connection.cycleRepeatMode() }
            waitUntil("repeat changes") { watcher.repeatMode != before }
        }
        waitUntil("repeat $mode in the UI state") { connection.state.value.repeatMode == mode }
    }

    private fun assertUninterrupted(index: Int, positionBefore: Long) {
        SystemClock.sleep(500)
        onMain {
            assertEquals(index, watcher.currentMediaItemIndex)
            assertTrue("still playing", watcher.isPlaying)
            assertTrue("position kept", watcher.currentPosition > positionBefore)
        }
        assertEquals("no interruption", emptyList<String>(), events.toList())
    }

    private fun seekNearEnd() = onMain { watcher.seekTo(watcher.duration - 400) }

    // --- shuffle -------------------------------------------------------------------------------

    @Test
    fun turningShuffleOnKeepsTheCurrentTrackAndThenPlaysEveryOtherTrackOnce() {
        playAndSettle(tracks, 3)
        val position = onMain { watcher.currentPosition }
        setShuffle(true)
        assertUninterrupted(3, position)
        val visited = mutableListOf(3)
        repeat(7) { visited += next() }
        assertEquals("every track once, starting with the current one", (0..7).toList(), visited.sorted())
        assertFalse("nothing left", onMain { watcher.hasNextMediaItem() })
        // The queue view shows that play order.
        val queue = connection.state.value.queue
        assertTrue(queue.shuffled)
        assertEquals(visited, queue.playOrder.toList())
    }

    @Test
    fun previousAndNextFollowTheShuffleOrder() {
        playAndSettle(tracks, 0)
        setShuffle(true)
        val first = next()
        val second = next()
        onMain { connection.previous() } // within the first seconds: back to the previous track
        waitUntil("previous in shuffle order") { watcher.currentMediaItemIndex == first && watcher.isPlaying }
        assertEquals(second, next())
    }

    @Test
    fun turningShuffleOffContinuesInQueueOrderFromTheCurrentTrack() {
        playAndSettle(tracks, 0)
        setShuffle(true)
        var current = next()
        while (current == 7) current = next() // need a track with a queue successor
        events.clear()
        val position = onMain { watcher.currentPosition }
        setShuffle(false)
        assertUninterrupted(current, position)
        assertEquals(current + 1, next())
        assertFalse(connection.state.value.queue.shuffled)
    }

    @Test
    fun duplicatesInTheQueueAreEachPlayedOnce() {
        val queue = listOf(tracks[0], tracks[1], tracks[0], tracks[2], tracks[1])
        playAndSettle(queue, 0)
        setShuffle(true)
        val visited = mutableListOf(0)
        repeat(4) { visited += next() }
        assertEquals((0..4).toList(), visited.sorted())
    }

    @Test
    fun tracksAddedWhileShufflingAreAllPlayed() {
        playAndSettle(tracks.take(5), 0)
        setShuffle(true)
        val visited = mutableListOf(0, next())
        onMain {
            assertTrue(connection.addToQueue(tracks[5]))
            assertTrue(connection.addToQueue(tracks[0])) // a duplicate entry too
        }
        waitUntil("added") { watcher.mediaItemCount == 7 }
        while (onMain { watcher.hasNextMediaItem() }) visited += next()
        assertEquals("all 7 entries once", (0..6).toList(), visited.sorted())
    }

    @Test
    fun removingWhileShufflingKeepsEveryOtherTrackOnce() {
        playAndSettle(tracks.take(6), 0)
        setShuffle(true)
        val visited = mutableListOf(0, next())
        val order = connection.state.value.queue.playOrder
        val upcoming = order[order.indexOf(index()) + 1]
        val removedId = onMain { watcher.getMediaItemAt(upcoming).mediaId }
        onMain { assertTrue(connection.removeFromQueue(connection.state.value.queue, upcoming)) }
        waitUntil("removed") { watcher.mediaItemCount == 5 }
        val ids = mutableListOf<String>()
        visited.forEach { _ -> } // indices before removal; compare by id from here on
        while (onMain { watcher.hasNextMediaItem() }) { next(); ids += onMain { watcher.currentMediaItem!!.mediaId } }
        assertFalse("removed track never plays", removedId in ids)
        assertEquals("no duplicates", ids.size, ids.toSet().size)
        assertEquals("the other 3 upcoming tracks play", 3, ids.size)
    }

    @Test
    fun clearingUpcomingWhileShufflingKeepsTheCurrentTrackPlaying() {
        playAndSettle(tracks.take(6), 0)
        setShuffle(true)
        val current = next()
        events.clear()
        val position = onMain { watcher.currentPosition }
        onMain { assertTrue(connection.clearUpcoming(connection.state.value.queue)) }
        waitUntil("cleared") { !watcher.hasNextMediaItem() }
        assertEquals(2, onMain { watcher.mediaItemCount }) // the first track and the current one remain
        assertEquals(tracks[0].id, onMain { watcher.getMediaItemAt(0).mediaId })
        val currentIndex = index()
        SystemClock.sleep(500)
        onMain {
            assertTrue(watcher.isPlaying)
            assertTrue(watcher.currentPosition > position)
        }
        assertTrue(current >= 0 && currentIndex in 0..1)
    }

    @Test
    fun reorderingIsNotOfferedWhileShuffling() {
        playAndSettle(tracks.take(5), 0)
        setShuffle(true)
        onMain { assertFalse(connection.moveInQueue(connection.state.value.queue, 3, 4)) }
        setShuffle(false)
        onMain { assertTrue(connection.moveInQueue(connection.state.value.queue, 3, 4)) }
    }

    // --- repeat --------------------------------------------------------------------------------

    @Test
    fun repeatOffEndsWhenTheQueueEnds() {
        playAndSettle(tracks.take(2), 1)
        setRepeat(Player.REPEAT_MODE_OFF)
        seekNearEnd()
        waitUntil("ended") { watcher.playbackState == Player.STATE_ENDED }
        assertEquals(1, index())
    }

    @Test
    fun repeatAllGoesBackToTheFirstTrackAfterTheLast() {
        playAndSettle(tracks.take(2), 1)
        setRepeat(Player.REPEAT_MODE_ALL)
        seekNearEnd()
        waitUntil("wrapped to the first track") { watcher.currentMediaItemIndex == 0 && watcher.isPlaying && watcher.currentPosition > 200 }
    }

    @Test
    fun repeatOneReplaysTheCurrentTrack() {
        playAndSettle(tracks.take(3), 0)
        setRepeat(Player.REPEAT_MODE_ONE)
        events.clear()
        seekNearEnd()
        waitUntil("same track again from the start") {
            watcher.currentMediaItemIndex == 0 && watcher.isPlaying && watcher.currentPosition in 100..3_000
        }
        assertTrue("repeated, not moved on", "transition:${Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT}" in events)
        // "Next" still moves on with repeat one.
        assertEquals(1, next())
    }

    @Test
    fun changingRepeatModeDuringPlaybackDoesNotInterrupt() {
        playAndSettle(tracks.take(3), 1)
        val position = onMain { watcher.currentPosition }
        for (expected in listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF)) {
            onMain { connection.cycleRepeatMode() }
            waitUntil("repeat $expected") { watcher.repeatMode == expected && connection.state.value.repeatMode == expected }
        }
        assertUninterrupted(1, position)
    }

    @Test
    fun shuffleWithRepeatAllStartsTheShuffleOrderAgain() {
        playAndSettle(tracks.take(3), 1)
        setShuffle(true)
        setRepeat(Player.REPEAT_MODE_ALL)
        val order = connection.state.value.queue.playOrder.toList()
        assertEquals(1, order.first())
        next(); next() // to the last track of the shuffle order
        assertEquals(order.last(), index())
        seekNearEnd()
        waitUntil("wrapped to the start of the shuffle order") { watcher.currentMediaItemIndex == order.first() && watcher.isPlaying && watcher.currentPosition > 200 }
    }
}
