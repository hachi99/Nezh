package com.hachicorp.nezh.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NezhShuffleOrderTest {

    private var current = C.INDEX_UNSET

    private fun order(start: Int, length: Int, seed: Int = 1) = NezhShuffleOrder.startingAt(start, length, { current }, Random(seed))

    private fun NezhShuffleOrder.traverse(): List<Int> {
        val out = ArrayList<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    private fun assertPermutation(order: List<Int>, length: Int) {
        assertEquals("every entry once", (0 until length).toList(), order.sorted())
    }

    @Test
    fun startsWithTheGivenTrackAndCoversEveryEntryOnce() {
        for (seed in 1..20) {
            val o = order(start = 5, length = 12, seed = seed)
            val t = o.traverse()
            assertEquals(5, t.first())
            assertPermutation(t, 12)
            assertEquals(t, o.playOrder())
            // previous walks back exactly
            assertEquals(t.reversed(), generateSequence(o.lastIndex) { o.getPreviousIndex(it).takeIf { p -> p != C.INDEX_UNSET } }.toList())
        }
    }

    @Test
    fun aNewQueueStartsAtTheTappedTrack() {
        val o = order(start = 0, length = 3).cloneAndSet(10, 7) as NezhShuffleOrder
        assertEquals(7, o.traverse().first())
        assertPermutation(o.traverse(), 10)
        val empty = order(0, 0)
        assertEquals(C.INDEX_UNSET, empty.firstIndex)
        assertEquals(C.INDEX_UNSET, empty.lastIndex)
    }

    @Test
    fun addedTracksAlwaysComeAfterTheCurrentTrack() {
        for (seed in 1..50) {
            val o = order(start = 4, length = 8, seed = seed)
            val played = o.traverse()
            current = played[5] // five tracks already played, the sixth is playing
            val after = o.cloneAndInsert(8, 3) as NezhShuffleOrder // three tracks appended to the queue
            val t = after.traverse()
            assertPermutation(t, 11)
            val p = t.indexOf(current)
            assertEquals("already played part unchanged", played.take(6), t.take(6))
            for (added in 8..10) assertTrue("added track $added after the current one", t.indexOf(added) > p)
        }
    }

    @Test
    fun insertingInTheMiddleShiftsIndicesAndKeepsTheCurrentTrackInPlace() {
        val o = order(start = 2, length = 5, seed = 3)
        val before = o.traverse()
        current = before[1]
        val after = (o.cloneAndInsert(1, 2) as NezhShuffleOrder).traverse()
        assertPermutation(after, 7)
        val shifted = before.map { if (it >= 1) it + 2 else it }
        val currentAfter = if (current >= 1) current + 2 else current
        assertEquals(shifted.take(2), after.take(2))
        assertTrue(after.indexOf(1) > after.indexOf(currentAfter) && after.indexOf(2) > after.indexOf(currentAfter))
    }

    @Test
    fun withNothingPlayingAddedTracksCanGoAnywhere() {
        current = C.INDEX_UNSET
        val o = order(C.INDEX_UNSET, 0).cloneAndInsert(0, 6) as NezhShuffleOrder
        assertPermutation(o.traverse(), 6)
    }

    @Test
    fun removingKeepsTheRelativeOrderOfTheRest() {
        val o = order(start = 0, length = 8, seed = 9)
        val before = o.traverse()
        val after = (o.cloneAndRemove(3, 5) as NezhShuffleOrder).traverse()
        val expected = before.filter { it !in 3..4 }.map { if (it >= 5) it - 2 else it }
        assertEquals(expected, after)
        assertEquals(emptyList<Int>(), (o.cloneAndClear() as NezhShuffleOrder).traverse())
    }

    @Test
    fun movingTracksInTheQueueKeepsEachTracksPlaceInTheOrder() {
        val o = order(start = 1, length = 6, seed = 4)
        val before = o.traverse()
        // Queue [0,1,2,3,4,5] -> move [4,5) to 1 -> [0,4,1,2,3,5]
        val after = (o.cloneAndMove(4, 5, 1) as NezhShuffleOrder).traverse()
        val newIndexOf = mapOf(0 to 0, 4 to 1, 1 to 2, 2 to 3, 3 to 4, 5 to 5)
        assertEquals(before.map { newIndexOf.getValue(it) }, after)
    }

    @Test
    fun duplicatesAreSeparateEntries() {
        // A queue [a, b, a, c]: four entries, all played.
        val t = order(start = 2, length = 4).traverse()
        assertPermutation(t, 4)
        assertEquals(2, t.first())
    }
}
