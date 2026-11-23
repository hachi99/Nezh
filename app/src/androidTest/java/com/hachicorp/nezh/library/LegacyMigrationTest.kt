package com.hachicorp.nezh.library

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real migration of the pre-index folder list (SharedPreferences, via FolderStore) into Room, run
 * by MusicLibrary.load() as on app start. Uses test-only preference and database names, so the
 * app's own data is never touched. SAF permissions are simulated with [FakePermissions] (granting
 * real persisted permissions needs the system picker).
 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test-legacy-${UUID.randomUUID()}"
    private val dbName = "test-migration-${UUID.randomUUID()}.db"
    private val legacy = FolderStore(context, prefsName)
    private val stacks = mutableListOf<TestLibrary>()

    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, id)

    /** The old list, in the user's order, with one folder accidentally repeated. */
    private val stored = listOf(
        StoredFolder(tree("music"), "Música"),
        StoredFolder(tree("podcasts"), "Podcasts"),
        StoredFolder(tree("music"), "Música"),
        StoredFolder(tree("audiobooks"), "Audiolibros"),
    )
    private val expectedIds = listOf("music", "podcasts", "audiobooks").map { FolderKey(TestTreeProvider.AUTHORITY, it).id }
    private val grants = setOf("music", "podcasts", "audiobooks").map { FolderKey(TestTreeProvider.AUTHORITY, it) }.toSet()

    @After
    fun tearDown() {
        stacks.forEach { it.close(delete = false) }
        context.deleteDatabase(dbName)
        context.deleteSharedPreferences(prefsName)
        java.io.File(context.filesDir, "test-artwork-$dbName").deleteRecursively()
    }

    /** Opens Nezh (a new library instance on the same database) and waits until it is loaded. */
    private fun launch(permissions: FakePermissions = FakePermissions(grants)): TestLibrary {
        val stack = TestLibrary(context, permissions, databaseName = dbName, legacyStore = legacy)
        stacks += stack
        runBlocking(Dispatchers.Main) { stack.library.load() }
        runBlocking { withTimeout(10_000) { stack.library.state.first { it.loaded } } }
        return stack
    }

    private fun closeApp(stack: TestLibrary) {
        stack.close(delete = false)
        stacks.remove(stack)
    }

    private fun foldersIn(stack: TestLibrary) = stack.db.folderDao().getAll()

    @Test
    fun migrationKeepsUrisOrderAndPermissionsWithoutDuplicates() {
        legacy.write(stored)
        val permissions = FakePermissions(grants)
        val stack = launch(permissions)

        val folders = foldersIn(stack)
        assertEquals(expectedIds, folders.map { it.id }) // order kept, duplicate dropped
        assertEquals(listOf(0, 1, 2), folders.map { it.position })
        assertEquals(listOf(tree("music"), tree("podcasts"), tree("audiobooks")).map { it.toString() }, folders.map { it.uri })
        assertEquals(listOf("Música", "Podcasts", "Audiolibros"), folders.map { it.name })
        // Permissions untouched: nothing released, and every folder is usable (not NO_PERMISSION).
        assertTrue(permissions.released.isEmpty())
        assertEquals(grants, permissions.persistedReadFolders())
        assertTrue(stack.library.state.value.folders.none { it.status == FolderStatus.NO_PERMISSION })
        // The old list is cleared once the import committed.
        assertTrue(legacy.read().isEmpty())
    }

    @Test
    fun reopeningTheAppDoesNotImportAgain() {
        legacy.write(stored)
        closeApp(launch())
        repeat(3) { closeApp(launch()) }
        val last = launch()
        assertEquals(expectedIds, foldersIn(last).map { it.id })
        assertEquals(listOf(0, 1, 2), foldersIn(last).map { it.position })
    }

    @Test
    fun interruptionAfterCommitBeforeClearingCausesNoDuplicates() {
        legacy.write(stored)
        closeApp(launch())
        // State left by a process death between the import commit and clearing the old list.
        legacy.write(stored)

        val stack = launch()
        assertEquals(expectedIds, foldersIn(stack).map { it.id })
        assertEquals(listOf(0, 1, 2), foldersIn(stack).map { it.position })
        assertTrue(legacy.read().isEmpty())
    }

    @Test
    fun failedImportKeepsTheOriginalListAndRetriesOnNextLaunch() {
        legacy.write(stored)
        // A real SQLite failure inside the import transaction.
        val failing = TestLibrary(context, FakePermissions(grants), databaseName = dbName, legacyStore = legacy)
        stacks += failing
        failing.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_import BEFORE INSERT ON folders BEGIN SELECT RAISE(ABORT, 'simulated failure'); END"
        )
        runBlocking(Dispatchers.Main) { failing.library.load() }
        val state = runBlocking { withTimeout(10_000) { failing.library.state.first { it.loaded } } }

        assertTrue("the library still loads", state.loaded)
        assertTrue("nothing half-imported", foldersIn(failing).isEmpty())
        assertEquals("original list kept", stored, legacy.read())

        failing.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_import")
        closeApp(failing)

        val retried = launch()
        assertEquals(expectedIds, foldersIn(retried).map { it.id })
        assertTrue(legacy.read().isEmpty())
    }
}
