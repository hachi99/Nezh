package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TitleOrderTest {

    private val folder = FolderKey("authority", "music")
    private val spanish = Locale.forLanguageTag("es-ES")

    private fun track(title: String, id: String = title, path: String = "Música/$id.mp3") =
        Track(id = id, documentUri = "content://x/$id", title = title, relativePath = path, folder = folder)

    private fun titles(tracks: List<Track>) = TitleOrder.sort(tracks, spanish).first.map { it.title }

    @Test
    fun sortsAscendingIgnoringCase() {
        assertEquals(
            listOf("alpha", "Bravo", "charlie", "Delta"),
            titles(listOf(track("Delta"), track("charlie"), track("alpha"), track("Bravo"))),
        )
    }

    @Test
    fun accentsAreASecondaryDifference() {
        // Dictionary order: "e" before "é", both before "f"; an accented title is not sent to the end.
        assertEquals(
            listOf("Eco", "Éxito", "Ezequiel", "Fuego"),
            titles(listOf(track("Fuego"), track("Éxito"), track("Ezequiel"), track("Eco"))),
        )
        assertEquals(listOf("cafe", "café"), titles(listOf(track("café"), track("cafe"))))
    }

    @Test
    fun ignoresLeadingPunctuationAndSpaces() {
        assertEquals(
            listOf("Azul", "¿Qué pasa?", "  Quizás", "(Remix) Sol"),
            titles(listOf(track("(Remix) Sol"), track("  Quizás"), track("¿Qué pasa?"), track("Azul"))),
        )
    }

    @Test
    fun nonLetterTitlesGoLastUnderOtherSection() {
        val (sorted, sections) = TitleOrder.sort(
            listOf(track("Zeta"), track("99 Luftballons"), track("Abba"), track("…"), track("東京"), track("1979")),
            spanish,
        )
        assertEquals(listOf("A", "Z", "#"), sections.map { it.label })
        assertEquals(listOf("Abba", "Zeta"), sorted.take(2).map { it.title })
        assertEquals(setOf("99 Luftballons", "…", "東京", "1979"), sorted.drop(2).map { it.title }.toSet())
    }

    @Test
    fun accentedAndTildeInitialsJoinTheirBaseLetter() {
        assertEquals("E", TitleOrder.sectionOf("Él"))
        assertEquals("N", TitleOrder.sectionOf("Ñandú"))
        assertEquals("A", TitleOrder.sectionOf("ábaco"))
        assertEquals("#", TitleOrder.sectionOf(""))
        assertEquals("#", TitleOrder.sectionOf("2 Become 1"))
    }

    @Test
    fun sectionsAreContiguousCompleteAndNeverRepeat() {
        val tracks = listOf(
            "beta", "Alfa", "ábside", "Banda", "#1", "Ñu", "nube", "zorro", "Zafiro", "árbol", "Óleo", "3",
        ).mapIndexed { i, t -> track(t, id = "t$i") }
        val (sorted, sections) = TitleOrder.sort(tracks, spanish)
        assertEquals(sections.map { it.label }.distinct(), sections.map { it.label })
        assertEquals(sorted.size, sections.sumOf { it.size })
        var next = 0
        sections.forEach { section ->
            assertEquals(next, section.start)
            (section.start until section.start + section.size).forEach { i ->
                assertEquals(section.label, TitleOrder.sectionOf(sorted[i].title))
            }
            next += section.size
        }
    }

    @Test
    fun equalTitlesHaveADeterministicOrder() {
        val a = track("Intro", id = "a", path = "Disco 2/intro.mp3")
        val b = track("Intro", id = "b", path = "Disco 1/intro.mp3")
        val c = track("INTRO", id = "c", path = "Disco 3/intro.mp3")
        val expected = listOf("c", "b", "a") // exact title ("INTRO" < "Intro"), then path
        listOf(listOf(a, b, c), listOf(c, a, b), listOf(b, c, a)).forEach { input ->
            assertEquals(expected, TitleOrder.sort(input, spanish).first.map { it.id })
        }
    }

    @Test
    fun emptyLibrary() {
        val (sorted, sections) = TitleOrder.sort(emptyList(), spanish)
        assertEquals(emptyList<Track>(), sorted)
        assertEquals(emptyList<TrackSection>(), sections)
    }
}
