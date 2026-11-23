package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ParallelWalkTest {

    private fun dir(id: String, name: String) = DocumentEntry(id, name, DIRECTORY_MIME_TYPE)
    private fun audio(id: String, name: String) = DocumentEntry(id, name, "audio/mpeg")

    /** 20 artists x 5 albums x 4 tracks, plus a .nomedia album, an unreadable album and a cycle. */
    private fun bigTree(): Map<String, List<DocumentEntry>> {
        val tree = HashMap<String, List<DocumentEntry>>()
        tree["root"] = List(20) { a -> dir("a$a", "Artist $a") }
        for (a in 0 until 20) {
            tree["a$a"] = List(5) { b -> dir("a$a/b$b", "Album $b") }
            for (b in 0 until 5) {
                tree["a$a/b$b"] = List(4) { t -> audio("a$a/b$b/t$t", "$t.mp3") }
            }
        }
        tree["a3/b1"] = tree.getValue("a3/b1") + DocumentEntry("n", ".nomedia", null)
        tree.remove("a7/b2") // unreadable
        tree["a9/b4"] = tree.getValue("a9/b4") + dir("root", "Loop")
        return tree
    }

    private fun lister(tree: Map<String, List<DocumentEntry>>): (String) -> List<DocumentEntry> = { id ->
        tree[id] ?: throw DirectoryListingException(SecurityException("unreadable"))
    }

    @Test
    fun parallelWalkMatchesSequentialWalk() {
        val tree = bigTree()
        val sequential = walkDocumentTree("root", lister(tree), parallelism = 1)
        val parallel = walkDocumentTree("root", lister(tree), parallelism = 4)

        assertEquals(sequential.files.toSet(), parallel.files.toSet())
        assertEquals(sequential.files.size, parallel.files.size)
        assertEquals(1, parallel.skippedDirectories)
        assertEquals(1, parallel.nomediaDirectories)
        assertEquals(sequential.directoriesListed, parallel.directoriesListed)
        assertEquals(20 * 5 * 4 - 4 - 4, parallel.files.size) // minus .nomedia album and unreadable album
    }

    @Test
    fun parallelWalkIsDeterministic() {
        val tree = bigTree()
        val first = walkDocumentTree("root", lister(tree), parallelism = 8).files
        repeat(5) { assertEquals(first, walkDocumentTree("root", lister(tree), parallelism = 8).files) }
    }

    @Test
    fun listingsActuallyRunConcurrently() {
        val tree = bigTree()
        val threads = Collections.synchronizedSet(HashSet<String>())
        walkDocumentTree("root", { id ->
            threads += Thread.currentThread().name
            Thread.sleep(2)
            lister(tree)(id)
        }, parallelism = 4)
        assertTrue("expected several worker threads, got $threads", threads.size > 1)
    }

    @Test(expected = DirectoryListingException::class)
    fun unreadableRootStillPropagatesInParallel() {
        walkDocumentTree("root", { throw DirectoryListingException(IllegalStateException()) }, parallelism = 4)
    }

    @Test(expected = IllegalStateException::class)
    fun unexpectedErrorsAreNotSwallowed() {
        val tree = bigTree()
        walkDocumentTree("root", { id ->
            if (id == "a5") throw IllegalStateException("bug")
            lister(tree)(id)
        }, parallelism = 4)
    }

    @Test
    fun progressReportsGrowingResults() {
        val sizes = mutableListOf<Int>()
        val result = walkDocumentTree("root", lister(bigTree()), parallelism = 4, onProgress = { sizes += it.size })
        assertEquals(sizes.sorted(), sizes)
        assertEquals(result.files.size, sizes.last())
    }
}
