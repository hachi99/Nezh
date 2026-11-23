package com.hachicorp.nezh.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRootsTest {

    private fun storage(documentId: String) = FolderKey(EXTERNAL_STORAGE_AUTHORITY, documentId)

    @Test
    fun primaryVolumeRootIsRejected() {
        assertTrue(isExternalStorageVolumeRoot(storage("primary:")))
    }

    @Test
    fun sdCardAndUsbVolumeRootsAreRejected() {
        assertTrue(isExternalStorageVolumeRoot(storage("1234-ABCD:")))
        assertTrue(isExternalStorageVolumeRoot(storage("39BD-07C5:")))
        // Some removable volumes use longer filesystem UUIDs.
        assertTrue(isExternalStorageVolumeRoot(storage("0123456789ABCDEF:")))
    }

    @Test
    fun rootWrittenWithSlashIsStillTheRoot() {
        assertTrue(isExternalStorageVolumeRoot(storage("primary:/")))
    }

    @Test
    fun foldersOnPrimaryStorageAreAccepted() {
        assertFalse(isExternalStorageVolumeRoot(storage("primary:Music")))
        assertFalse(isExternalStorageVolumeRoot(storage("primary:Music/Rock")))
        assertFalse(isExternalStorageVolumeRoot(storage("primary:Download/Albums")))
    }

    @Test
    fun foldersOnSdCardsAreAccepted() {
        assertFalse(isExternalStorageVolumeRoot(storage("1234-ABCD:Music")))
        assertFalse(isExternalStorageVolumeRoot(storage("1234-ABCD:Music/Rock")))
    }

    @Test
    fun namesThatOnlyLookLikeRootsAreAccepted() {
        // Structural parsing, not substring matching.
        assertFalse(isExternalStorageVolumeRoot(storage("primary:primary:")))
        assertFalse(isExternalStorageVolumeRoot(storage("primary:Music:")))
        assertFalse(isExternalStorageVolumeRoot(storage("primary:Music/1234-ABCD:")))
    }

    @Test
    fun homeRootIsTheDocumentsFolderNotAVolume() {
        // Android 8.0–10: "home:" is the Documents directory of primary storage.
        assertFalse(isExternalStorageVolumeRoot(storage("home:")))
        assertFalse(isExternalStorageVolumeRoot(storage("home:Music")))
    }

    @Test
    fun malformedIdsAreNotTreatedAsRoots() {
        assertFalse(isExternalStorageVolumeRoot(storage("primary")))
        assertFalse(isExternalStorageVolumeRoot(storage(":")))
        assertFalse(isExternalStorageVolumeRoot(storage("")))
    }

    @Test
    fun otherProvidersAreNeverRejected() {
        assertFalse(isExternalStorageVolumeRoot(FolderKey("com.android.providers.downloads.documents", "downloads")))
        assertFalse(isExternalStorageVolumeRoot(FolderKey("com.example.cloud.documents", "primary:")))
        assertFalse(isExternalStorageVolumeRoot(FolderKey("com.example.usb", "root:")))
    }
}
