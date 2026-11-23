package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPlannerTest {

    private fun seen(path: String, size: Long? = 100, date: Long? = 1, uri: String = "uri:$path") =
        SeenFile("p::$path", path, path.substringAfterLast('/'), "audio/mpeg", size, date, path, uri)

    private fun known(path: String, size: Long? = 100, date: Long? = 1, read: Boolean = true, uri: String = "uri:$path") =
        KnownState("p::$path", path, uri, size, date, read, if (read) size else null, if (read) date else null)

    @Test
    fun firstScanAddsEverything() {
        val plan = planFolderScan(emptyList(), listOf(seen("a.mp3"), seen("Rock/b.mp3")), emptyList())
        assertEquals(2, plan.added.size)
        assertTrue(plan.removed.isEmpty() && plan.updated.isEmpty() && plan.metadataStale.isEmpty())
    }

    @Test
    fun unchangedFilesProduceNoWork() {
        val plan = planFolderScan(listOf(known("a.mp3")), listOf(seen("a.mp3")), emptyList())
        assertTrue(plan.added.isEmpty() && plan.updated.isEmpty() && plan.metadataStale.isEmpty() && plan.removed.isEmpty())
    }

    @Test
    fun sizeOrDateChangeMarksMetadataStale() {
        val plan = planFolderScan(
            listOf(known("a.mp3"), known("b.mp3")),
            listOf(seen("a.mp3", size = 101), seen("b.mp3", date = 2)),
            emptyList(),
        )
        assertEquals(setOf("p::a.mp3", "p::b.mp3"), plan.metadataStale)
        assertEquals(2, plan.updated.size)
    }

    @Test
    fun unknownAttributesAreConservative() {
        val plan = planFolderScan(listOf(known("a.mp3")), listOf(seen("a.mp3", size = null, date = null)), emptyList())
        assertTrue("not re-read without reliable attributes", plan.metadataStale.isEmpty())
        assertEquals(1, plan.updated.size) // attributes are still recorded as unknown
    }

    @Test
    fun pendingMetadataIsNotMarkedStaleAgain() {
        val plan = planFolderScan(listOf(known("a.mp3", read = false)), listOf(seen("a.mp3", size = 5)), emptyList())
        assertTrue(plan.metadataStale.isEmpty())
    }

    @Test
    fun missingFilesAreRemovedWhenTheirDirectoryWasListed() {
        val plan = planFolderScan(listOf(known("a.mp3"), known("Rock/b.mp3")), listOf(seen("a.mp3")), emptyList())
        assertEquals(listOf("p::Rock/b.mp3"), plan.removed)
    }

    @Test
    fun missingFilesUnderAFailedDirectoryAreKept() {
        val plan = planFolderScan(
            listOf(known("a.mp3"), known("Rock/b.mp3"), known("Rock/Live/c.mp3"), known("Rocky/d.mp3")),
            listOf(seen("a.mp3")),
            failedDirectories = listOf("Rock/"),
        )
        assertEquals(listOf("p::Rocky/d.mp3"), plan.removed) // "Rocky/" is not under "Rock/"
        assertEquals(2, plan.keptUnverified)
    }

    @Test
    fun movedFileWithStableIdIsUpdatedNotReadded() {
        // Providers with opaque ids keep the id across moves.
        val before = known("x").copy(innerPath = "Old/song.mp3", documentUri = "uri:old")
        val now = seen("x").copy(innerPath = "New/song.mp3", documentUri = "uri:new")
        val plan = planFolderScan(listOf(before), listOf(now), emptyList())
        assertEquals(listOf(now), plan.updated)
        assertTrue(plan.added.isEmpty() && plan.removed.isEmpty() && plan.metadataStale.isEmpty())
    }

    @Test
    fun renameOnPathBasedProviderIsRemovalPlusAddition() {
        val plan = planFolderScan(listOf(known("Rock/old.mp3")), listOf(seen("Rock/new.mp3")), emptyList())
        assertEquals(listOf("p::Rock/old.mp3"), plan.removed)
        assertEquals(listOf("p::Rock/new.mp3"), plan.added.map { it.trackId })
    }

    @Test
    fun nomediaExcludedTracksAreRemovedAndComeBackAsNew() {
        // A .nomedia directory is listed successfully: its tracks are simply not seen.
        val excluded = planFolderScan(listOf(known("a.mp3"), known("Voice/v.mp3")), listOf(seen("a.mp3")), emptyList())
        assertEquals(listOf("p::Voice/v.mp3"), excluded.removed)
        val restored = planFolderScan(listOf(known("a.mp3")), listOf(seen("a.mp3"), seen("Voice/v.mp3")), emptyList())
        assertEquals(listOf("p::Voice/v.mp3"), restored.added.map { it.trackId })
    }
}
