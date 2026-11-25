package com.hachicorp.nezh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the queue drag arithmetic used by QueueTab (rows 100 px tall in these examples). */
class QueueDragTest {

    private val row = 100f

    @Test
    fun theTargetMovesByWholeRowsRoundingToTheNearest() {
        // Queue of 10, upcoming tracks start at 3 (current at 2).
        assertEquals(5, dragTarget(from = 5, dy = 0f, rowPx = row, firstUpcoming = 3, size = 10))
        assertEquals(5, dragTarget(from = 5, dy = 49f, rowPx = row, firstUpcoming = 3, size = 10))
        assertEquals(6, dragTarget(from = 5, dy = 51f, rowPx = row, firstUpcoming = 3, size = 10))
        assertEquals(3, dragTarget(from = 5, dy = -240f, rowPx = row, firstUpcoming = 3, size = 10))
    }

    @Test
    fun theTargetStaysWithinTheUpcomingTracks() {
        // Never above the first upcoming track (the current one cannot be displaced)...
        assertEquals(3, dragTarget(from = 4, dy = -900f, rowPx = row, firstUpcoming = 3, size = 10))
        // ...nor past the end of the queue.
        assertEquals(9, dragTarget(from = 8, dy = 900f, rowPx = row, firstUpcoming = 3, size = 10))
    }

    @Test
    fun movingDownShiftsTheRowsInBetweenUp() {
        // Item at 3 held over 6: rows 3..5 show the items from 4..6, 6 shows the held item.
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 3, 7), (0..7).map { dragSourceAt(it, from = 3, target = 6) })
    }

    @Test
    fun movingUpShiftsTheRowsInBetweenDown() {
        assertEquals(listOf(0, 1, 2, 6, 3, 4, 5, 7), (0..7).map { dragSourceAt(it, from = 6, target = 3) })
    }

    @Test
    fun notMovedShowsEveryRowAsIs() {
        assertEquals((0..7).toList(), (0..7).map { dragSourceAt(it, from = 4, target = 4) })
    }

    // --- A drag is only valid on the queue it started on ----------------------------------------

    private val queueA = Any() // stands for the QueueSnapshot instance shown when the drag began
    private val queueB = Any() // any later snapshot: every queue change produces a new one

    @Test
    fun aValidDragGivesItsSourceAndTarget() {
        assertEquals(
            DragPositions(from = 4, target = 6),
            dragPositions(startedOn = queueA, current = queueA, from = 4, dy = 210f, rowPx = row, firstUpcoming = 3, size = 10),
        )
    }

    @Test
    fun aDragIsDroppedWhenTheQueueChangesSize() {
        // Started on a queue of 10; the queue shown now has 5 items (a new snapshot).
        assertEquals(null, dragPositions(queueA, queueB, from = 8, dy = 0f, rowPx = row, firstUpcoming = 1, size = 5))
    }

    @Test
    fun aDragIsDroppedWhenATrackIsRemovedEvenIfItsIndexStillExists() {
        // A track before the dragged one was removed: index 4 now holds a different track.
        assertEquals(null, dragPositions(queueA, queueB, from = 4, dy = 0f, rowPx = row, firstUpcoming = 3, size = 9))
    }

    @Test
    fun aDragWithAnIndexOutsideTheUpcomingTracksIsDropped() {
        // Same snapshot, but the dragged index is past the end or no longer upcoming.
        assertEquals(null, dragPositions(queueA, queueA, from = 10, dy = 0f, rowPx = row, firstUpcoming = 3, size = 10))
        assertEquals(null, dragPositions(queueA, queueA, from = 2, dy = 0f, rowPx = row, firstUpcoming = 3, size = 10))
    }

    @Test
    fun theTargetOfAValidDragIsAlwaysInsideTheQueue() {
        for (dy in listOf(-5_000f, -150f, 0f, 150f, 5_000f)) {
            val p = dragPositions(queueA, queueA, from = 5, dy = dy, rowPx = row, firstUpcoming = 3, size = 10)!!
            assertTrue("target ${p.target} for dy $dy", p.target in 3 until 10)
            assertTrue((3 until 10).all { dragSourceAt(it, p.from, p.target) in 0 until 10 })
        }
    }
}
