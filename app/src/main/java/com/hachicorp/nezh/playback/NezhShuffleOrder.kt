package com.hachicorp.nezh.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The order in which shuffle plays the queue (the queue itself keeps its order). Rules:
 * - The current track goes first: turning shuffle on, or starting a new queue with shuffle on,
 *   never replays the current track and never leaves tracks "behind" it that would not play.
 * - Every queue entry is played once before the order ends (duplicates are separate entries).
 * - Tracks added while shuffling go to random positions *after* the current track, so they
 *   always play; removed tracks leave the rest of the order unchanged; moving tracks in the queue
 *   keeps each track's place in the order.
 *
 * [currentIndex] gives the player's current item index at the time of a change (ExoPlayer asks for
 * new orders on the player's thread, before it updates its own state). Immutable; every change
 * returns a new instance, as [ShuffleOrder] requires.
 */
@OptIn(UnstableApi::class)
internal class NezhShuffleOrder private constructor(
    /** shuffled[p] = queue index played at position p. */
    private val shuffled: IntArray,
    private val currentIndex: () -> Int,
    private val random: Random,
) : ShuffleOrder {

    /** position[i] = where queue index i is played. */
    private val position = IntArray(shuffled.size).also { pos -> shuffled.forEachIndexed { p, index -> pos[index] = p } }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int =
        position[index].let { p -> if (p + 1 < shuffled.size) shuffled[p + 1] else C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int =
        position[index].let { p -> if (p > 0) shuffled[p - 1] else C.INDEX_UNSET }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.last()

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.first()

    /** A whole new queue of [insertionCount] tracks, starting at [startIndex]. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
        NezhShuffleOrder(startingAt(startIndex, insertionCount, random), currentIndex, random)

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val current = currentIndex()
        // Existing entries at or after the insertion point move up.
        val order = shuffled.mapTo(ArrayList(shuffled.size + insertionCount)) { if (it >= insertionIndex) it + insertionCount else it }
        val currentAfter = when {
            current == C.INDEX_UNSET || current >= shuffled.size -> C.INDEX_UNSET
            current >= insertionIndex -> current + insertionCount
            else -> current
        }
        // Random positions strictly after the current track's position.
        val after = if (currentAfter == C.INDEX_UNSET) -1 else order.indexOf(currentAfter)
        for (index in insertionIndex until insertionIndex + insertionCount) {
            order.add(random.nextInt(after + 1, order.size + 1), index)
        }
        return NezhShuffleOrder(order.toIntArray(), currentIndex, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        val order = shuffled.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - removed else it }
        return NezhShuffleOrder(order.toIntArray(), currentIndex, random)
    }

    /** The queue moved [indexFrom, indexToExclusive) to start at [newIndexFrom]: same order, new indices. */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val queue = MutableList(shuffled.size) { it }
        val block = queue.subList(indexFrom, indexToExclusive).toList()
        repeat(block.size) { queue.removeAt(indexFrom) }
        queue.addAll(newIndexFrom, block)
        val newIndexOf = IntArray(shuffled.size).also { map -> queue.forEachIndexed { newIndex, oldIndex -> map[oldIndex] = newIndex } }
        return NezhShuffleOrder(IntArray(shuffled.size) { newIndexOf[shuffled[it]] }, currentIndex, random)
    }

    override fun cloneAndClear(): ShuffleOrder = NezhShuffleOrder(IntArray(0), currentIndex, random)

    /** Queue indices in play order (for tests and diagnostics). */
    internal fun playOrder(): List<Int> = shuffled.toList()

    companion object {
        /** An order for [length] tracks that starts with [startIndex] (or fully random if unset). */
        fun startingAt(startIndex: Int, length: Int, currentIndex: () -> Int, random: Random = Random.Default): NezhShuffleOrder =
            NezhShuffleOrder(startingAt(startIndex, length, random), currentIndex, random)

        private fun startingAt(startIndex: Int, length: Int, random: Random): IntArray {
            val rest = (0 until length).filter { it != startIndex }.shuffled(random)
            return (if (startIndex in 0 until length) listOf(startIndex) + rest else rest).toIntArray()
        }
    }
}
