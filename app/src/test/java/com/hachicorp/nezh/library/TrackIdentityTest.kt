package com.hachicorp.nezh.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackIdentityTest {

    private val storage = "com.android.externalstorage.documents"

    @Test
    fun sameDocumentThroughNestedFoldersHasOneIdentity() {
        // Reached through tree "primary:Music" or tree "primary:Music/Rock": same document id.
        assertEquals(TrackIdentity.of(storage, "primary:Music/Rock/a.mp3"), TrackIdentity.of(storage, "primary:Music/Rock/a.mp3"))
    }

    @Test
    fun sameDocumentIdInDifferentProvidersDoesNotCollide() {
        assertNotEquals(TrackIdentity.of(storage, "42"), TrackIdentity.of("com.example.cloud", "42"))
    }

    @Test
    fun differentFilesHaveDifferentIdentities() {
        assertNotEquals(TrackIdentity.of(storage, "primary:Music/a.mp3"), TrackIdentity.of(storage, "primary:Music/b.mp3"))
        // A rename on a path-based provider changes the id (handled as removal + addition).
        assertNotEquals(TrackIdentity.of(storage, "primary:Music/old.mp3"), TrackIdentity.of(storage, "primary:Music/new.mp3"))
    }

    @Test
    fun parseRoundTripsIncludingSeparatorsInsideTheDocumentId() {
        val id = TrackIdentity.of(storage, "primary:Music/a::b.mp3")
        assertEquals(storage to "primary:Music/a::b.mp3", TrackIdentity.parse(id))
    }

    @Test
    fun parseRejectsNonTrackIds() {
        assertNull(TrackIdentity.parse("no-separator"))
        assertNull(TrackIdentity.parse("::doc"))
        assertNull(TrackIdentity.parse("authority::"))
    }

    @Test
    fun folderIdUsesTheSameScheme() {
        assertEquals("$storage::primary:Music", FolderKey(storage, "primary:Music").id)
    }
}
