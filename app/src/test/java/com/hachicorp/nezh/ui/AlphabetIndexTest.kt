package com.hachicorp.nezh.ui

import com.hachicorp.nezh.library.TrackSection
import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetIndexTest {

    @Test
    fun indexHasTheLettersThenTheOtherSection() {
        assertEquals(27, IndexLabels.size)
        assertEquals("A", IndexLabels.first())
        assertEquals("Z", IndexLabels[25])
        assertEquals(TrackSection.OTHER, IndexLabels.last())
    }

    @Test
    fun headerPositionsCountTheHeadersBeforeEachSection() {
        // List: [A] a a a [C] c [#] x x  -> A at 0, C at 4, # at 6.
        val sections = listOf(TrackSection("A", 0, 3), TrackSection("C", 3, 1), TrackSection("#", 4, 2))
        assertEquals(mapOf("A" to 0, "C" to 4, "#" to 6), sectionHeaderPositions(sections))
        assertEquals(emptyMap<String, Int>(), sectionHeaderPositions(emptyList()))
    }

    @Test
    fun letterUnderTheFingerIsEvenlySpacedAndClamped() {
        val height = 2700 // 100 px per letter
        assertEquals("A", indexLabelAt(0f, height))
        assertEquals("A", indexLabelAt(99f, height))
        assertEquals("B", indexLabelAt(100f, height))
        assertEquals("O", indexLabelAt(1450f, height))
        assertEquals("Z", indexLabelAt(2550f, height))
        assertEquals("#", indexLabelAt(2650f, height))
        // A finger that slides past either end stays on the first / last letter.
        assertEquals("A", indexLabelAt(-300f, height))
        assertEquals("#", indexLabelAt(5000f, height))
        assertEquals("A", indexLabelAt(10f, 0))
    }
}
