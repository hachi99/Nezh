package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTreeWalkerTest {

    private fun dir(id: String, name: String) = DocumentEntry(id, name, DIRECTORY_MIME_TYPE)
    private fun audio(id: String, name: String) = DocumentEntry(id, name, "audio/mpeg")

    @Test
    fun walksSubdirectoriesRecursivelyWithRelativePaths() {
        val tree = mapOf(
            "root" to listOf(audio("a", "a.mp3"), dir("rock", "Rock"), DocumentEntry("img", "cover.jpg", "image/jpeg")),
            "rock" to listOf(audio("b", "b.mp3"), dir("live", "Live")),
            "live" to listOf(audio("c", "c.mp3")),
        )
        val result = walkDocumentTree("root", { tree.getValue(it) })

        assertEquals(0, result.skippedDirectories)
        assertEquals(
            setOf(
                FoundAudio("a", "a.mp3", ""),
                FoundAudio("b", "b.mp3", "Rock/"),
                FoundAudio("c", "c.mp3", "Rock/Live/"),
            ),
            result.files.map { it.withoutAttributes() }.toSet(),
        )
    }

    @Test
    fun onlyListsDescendantsOfTheRoot() {
        val listed = mutableListOf<String>()
        val tree = mapOf("root" to listOf(dir("sub", "Sub")), "sub" to emptyList<DocumentEntry>())
        walkDocumentTree("root", { listed += it; tree.getValue(it) })
        assertEquals(listOf("root", "sub"), listed)
    }

    @Test
    fun cyclesDoNotLoop() {
        val tree = mapOf(
            "root" to listOf(dir("a", "A")),
            "a" to listOf(dir("root", "BackToRoot"), audio("x", "x.mp3")),
        )
        val result = walkDocumentTree("root", { tree.getValue(it) })
        assertEquals(listOf(FoundAudio("x", "x.mp3", "A/")), result.files.map { it.withoutAttributes() })
    }

    @Test
    fun unreadableSubdirectoryIsSkippedAndCounted() {
        val tree = mapOf(
            "root" to listOf(dir("bad", "Bad"), audio("a", "a.mp3")),
        )
        val result = walkDocumentTree("root", { id ->
            tree[id] ?: throw DirectoryListingException(SecurityException("denied"))
        })
        assertEquals(1, result.skippedDirectories)
        assertEquals(listOf("Bad/"), result.failedDirectories) // its records must be kept
        assertEquals(listOf(FoundAudio("a", "a.mp3", "")), result.files.map { it.withoutAttributes() })
    }

    @Test(expected = DirectoryListingException::class)
    fun unreadableRootPropagates() {
        walkDocumentTree("root", { throw DirectoryListingException(IllegalStateException()) })
    }

    @Test
    fun hiddenDirectoriesAreNotEntered() {
        val tree = mapOf("root" to listOf(dir("trash", ".Trash")))
        val listed = mutableListOf<String>()
        walkDocumentTree("root", { listed += it; tree.getValue(it) })
        assertEquals(listOf("root"), listed)
    }

    @Test
    fun cancellationStopsTheWalk() {
        var calls = 0
        val tree = mapOf("root" to listOf(dir("a", "A")), "a" to emptyList<DocumentEntry>())
        val thrown = runCatching {
            walkDocumentTree("root", { tree.getValue(it) }, checkCancelled = {
                if (++calls > 1) throw IllegalStateException("cancelled")
            })
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun mergeUniqueKeepsFirstOccurrenceInFolderOrder() {
        // "Music" and its subfolder "Music/Rock" both selected: Rock's tracks appear once.
        val music = listOf("m1", "r1", "r2")
        val rock = listOf("r1", "r2", "r3")
        assertEquals(listOf("m1", "r1", "r2", "r3"), mergeUnique(listOf(music, rock)) { it })
    }

    /** Compares identity and location only; provider attributes are covered elsewhere. */
    private fun FoundAudio.withoutAttributes() = FoundAudio(documentId, displayName, relativeDir)
}
