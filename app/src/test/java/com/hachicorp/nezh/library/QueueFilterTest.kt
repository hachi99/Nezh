package com.hachicorp.nezh.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueFilterTest {

    private val music = FolderKey("provider", "primary:Music")
    private val podcasts = FolderKey("provider", "primary:Podcasts")
    private val removed = FolderKey("provider", "primary:Old")

    @Test
    fun trackExcludedByRescanLeavesTheQueue() {
        // "v" was in the queue; a rescan of Music found .nomedia in its directory.
        val filter = QueueFilter(
            permittedFolders = setOf(music),
            scannedFolders = setOf(music),
            libraryTrackIds = setOf("a", "r"),
        )
        assertFalse(filter.allows("v", music))
        assertTrue(filter.allows("a", music))
    }

    @Test
    fun trackRestoredByRescanIsAllowedAgain() {
        val filter = QueueFilter(setOf(music), setOf(music), setOf("a", "v", "r"))
        assertTrue(filter.allows("v", music))
    }

    @Test
    fun tracksOfFolderStillScanningOrUnavailableAreKept() {
        val filter = QueueFilter(
            permittedFolders = setOf(music, podcasts),
            scannedFolders = setOf(music),
            libraryTrackIds = setOf("a"),
        )
        assertTrue(filter.allows("p1", podcasts))
    }

    @Test
    fun tracksOfRemovedOrRevokedFoldersLeaveTheQueue() {
        val filter = QueueFilter(setOf(music), setOf(music), setOf("x"))
        assertFalse(filter.allows("x", removed))
        assertFalse(filter.allows("x", null))
    }
}
