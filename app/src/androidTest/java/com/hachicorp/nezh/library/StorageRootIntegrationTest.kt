package com.hachicorp.nezh.library

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Runs [MusicLibrary.addFolder] on a real device with tree URIs a picker could return.
 *
 * Deterministic regardless of what the device already granted to Nezh:
 * - Volume roots are rejected before any permission is taken, so existing grants (including the
 *   residual root grant Android 10 keeps after the picker) cannot change the outcome.
 * - Normal folders use a unique, nonexistent path per run, which Nezh can never hold a persisted
 *   grant for. They pass the root check and then fail at the permission step, which is the
 *   observable proof that the root check did not reject them.
 * Every test also verifies that nothing was saved and no persisted permission remains.
 */
@RunWith(AndroidJUnit4::class)
class StorageRootIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Real SAF permissions: the test relies on Nezh never holding a grant for these folders.
    private val stack = TestLibrary(context, SafFolderPermissions(SafTreeScanner(context.contentResolver)))

    /** Unique per run, so no earlier grant or stored folder can match it. */
    private val testDir = "NezhTest-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        stack.close()
    }

    private fun treeUri(documentId: String): Uri =
        DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)

    private fun addFolderAndAwaitNotice(uri: Uri): LibraryNotice = runBlocking {
        val library = stack.library
        library.dismissNotice()
        library.addFolder(uri)
        withTimeout(5_000) { library.state.first { it.notice != null } }.notice!!
    }

    private fun assertLeavesNoTrace(uri: Uri) {
        val key = FolderKey.of(uri)
        assertFalse(
            "folder was saved",
            stack.db.folderDao().getAll().any { it.id == key?.id },
        )
        assertFalse(
            "persisted permission remains",
            context.contentResolver.persistedUriPermissions.any { FolderKey.of(it.uri) == key },
        )
    }

    private fun assertRejectedAsRoot(documentId: String) {
        val uri = treeUri(documentId)
        assertEquals(LibraryNotice.StorageRootNotAllowed, addFolderAndAwaitNotice(uri))
        assertLeavesNoTrace(uri)
    }

    private fun assertPassesRootCheck(documentId: String) {
        val uri = treeUri(documentId)
        assertEquals(LibraryNotice.PermissionNotPersisted, addFolderAndAwaitNotice(uri))
        assertLeavesNoTrace(uri)
    }

    @Test
    fun primaryVolumeRootIsRejected() = assertRejectedAsRoot("primary:")

    @Test
    fun sdCardVolumeRootIsRejected() = assertRejectedAsRoot("0000-0000:")

    @Test
    fun musicFolderPassesTheRootCheck() = assertPassesRootCheck("primary:$testDir/Music")

    @Test
    fun nestedMusicFolderPassesTheRootCheck() = assertPassesRootCheck("primary:$testDir/Music/Rock")

    @Test
    fun sdCardFolderPassesTheRootCheck() = assertPassesRootCheck("0000-0000:$testDir/Music")
}
