package com.hachicorp.nezh.library.artwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val name = "0123456789abcdef0123456789abcdef.jpg"

    private fun store() = ArtworkStore(File(temp.root, "artwork"))

    @Test
    fun writeIsAtomicAndReportsRealSize() {
        val store = store()
        val bytes = ByteArray(1234) { it.toByte() }
        assertEquals(1234L, store.write(name, bytes))
        assertArrayEquals(bytes, store.fileFor(name).readBytes())
        assertTrue("no temporary file left", File(store.directory, "tmp").listFiles()!!.isEmpty())
        assertEquals(listOf(name), store.listFileNames())
    }

    @Test
    fun fileIsNotDeletedWhileBeingRead() {
        val store = store()
        store.write(name, byteArrayOf(1, 2, 3))
        store.read(name) {
            assertFalse("delete refused while reading", store.delete(name))
            assertTrue(store.exists(name))
        }
        assertTrue(store.delete(name))
        assertFalse(store.exists(name))
    }

    @Test
    fun interruptedWritesAreCleanedUp() {
        val store = store()
        File(store.directory, "tmp/$name.tmp").writeBytes(byteArrayOf(1, 2)) // crash mid-write
        assertEquals(1, store.deleteTemporaryFiles())
        assertTrue(store.listFileNames().isEmpty())
    }

    @Test
    fun onlyHashNamesAreAccepted() {
        assertTrue(ArtworkStore.isValidName(name))
        assertFalse(ArtworkStore.isValidName("../escape.jpg"))
        assertFalse(ArtworkStore.isValidName("cover.jpg"))
    }
}

class EvictionPlanTest {

    private fun entry(hash: String, size: Long, at: Long) = CachedArtwork(hash, size, at)

    @Test
    fun nothingIsEvictedWithinBudget() {
        assertTrue(planEviction(listOf(entry("a", 40, 1), entry("b", 60, 2)), 100, emptySet()).isEmpty())
    }

    @Test
    fun leastRecentlyUsedAreEvictedFirstUntilWithinBudget() {
        val entries = listOf(entry("new", 40, 30), entry("old", 40, 10), entry("mid", 40, 20))
        assertEquals(listOf("old"), planEviction(entries, 80, emptySet()))
        assertEquals(listOf("old", "mid"), planEviction(entries, 40, emptySet()))
    }

    @Test
    fun protectedCoversAreNeverEvicted() {
        val entries = listOf(entry("playing", 40, 1), entry("other", 40, 2), entry("new", 40, 3))
        assertEquals(listOf("other"), planEviction(entries, 80, setOf("playing")))
    }

    @Test
    fun budgetMayStayExceededWhenOnlyProtectedCoversRemain() {
        assertTrue(planEviction(listOf(entry("playing", 200, 1)), 100, setOf("playing")).isEmpty())
    }
}
