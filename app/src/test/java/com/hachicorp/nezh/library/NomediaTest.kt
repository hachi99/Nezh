package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NomediaTest {

    private fun dir(id: String, name: String) = DocumentEntry(id, name, DIRECTORY_MIME_TYPE)
    private fun audio(id: String, name: String) = DocumentEntry(id, name, "audio/mpeg")
    private val nomedia = DocumentEntry("nomedia-file", ".nomedia", "application/octet-stream")

    /** Music/ { a.mp3, Voice/ { v.mp3, Old/ { o.mp3 } }, Rock/ { r.mp3 } } */
    private fun library(): MutableMap<String, List<DocumentEntry>> = mutableMapOf(
        "music" to listOf(audio("a", "a.mp3"), dir("voice", "Voice"), dir("rock", "Rock")),
        "voice" to listOf(audio("v", "v.mp3"), dir("old", "Old")),
        "old" to listOf(audio("o", "o.mp3")),
        "rock" to listOf(audio("r", "r.mp3")),
    )

    private fun scan(tree: Map<String, List<DocumentEntry>>, listed: MutableList<String> = mutableListOf()) =
        walkDocumentTree("music", { id -> listed += id; tree.getValue(id) })

    private fun WalkResult.ids() = files.map { it.documentId }.toSet()

    @Test
    fun directoryWithoutNomediaIsIndexed() {
        val result = scan(library())
        assertEquals(setOf("a", "v", "o", "r"), result.ids())
        assertEquals(0, result.nomediaDirectories)
    }

    @Test
    fun directoryWithNomediaIsExcluded() {
        val tree = library().apply { put("voice", getValue("voice") + nomedia) }
        val result = scan(tree)
        assertFalse("v" in result.ids())
        assertEquals(setOf("a", "r"), result.ids()) // Old/ is inside Voice/, so "o" is excluded too
        assertEquals(1, result.nomediaDirectories)
    }

    @Test
    fun subdirectoriesOfNomediaDirectoryAreNotVisited() {
        val tree = library().apply { put("voice", getValue("voice") + nomedia) }
        val listed = mutableListOf<String>()
        val result = scan(tree, listed)
        assertFalse("old" in listed)
        assertFalse("o" in result.ids())
        assertTrue("r" in result.ids()) // siblings are unaffected
    }

    @Test
    fun nomediaInRootExcludesTheWholeFolder() {
        val tree = library().apply { put("music", getValue("music") + nomedia) }
        val listed = mutableListOf<String>()
        val result = scan(tree, listed)
        assertTrue(result.files.isEmpty())
        assertEquals(listOf("music"), listed)
    }

    @Test
    fun nomediaMatchIsCaseInsensitive() {
        val tree = library().apply {
            put("rock", getValue("rock") + DocumentEntry("n", ".NOMEDIA", null))
        }
        assertFalse("r" in scan(tree).ids())
    }

    @Test
    fun nomediaAddedAfterAPreviousScanRemovesTracksOnRescan() {
        val tree = library()
        assertTrue("v" in scan(tree).ids())

        tree["voice"] = tree.getValue("voice") + nomedia
        val rescan = scan(tree)

        assertFalse("v" in rescan.ids())
        assertFalse("o" in rescan.ids())
        assertEquals(setOf("a", "r"), rescan.ids())
    }

    @Test
    fun nomediaRemovedRestoresTracksOnRescan() {
        val tree = library().apply { put("voice", getValue("voice") + nomedia) }
        assertFalse("v" in scan(tree).ids())

        tree["voice"] = tree.getValue("voice") - nomedia
        val rescan = scan(tree)

        assertEquals(setOf("a", "v", "o", "r"), rescan.ids())
        assertEquals(0, rescan.nomediaDirectories)
    }
}
