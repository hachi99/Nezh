package com.hachicorp.nezh.library

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.db.FolderScanStatus
import com.hachicorp.nezh.library.db.MetadataState
import com.hachicorp.nezh.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests of the persistent library index: real Room database, real scanner and indexer,
 * real cross-process SAF-style queries against [TestTreeProvider]. Permissions are simulated with
 * [FakePermissions]; metadata with a counting fake reader (real reading is covered by
 * MetadataReaderTest).
 */
@RunWith(AndroidJUnit4::class)
class LibraryIndexTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stacks = mutableListOf<TestLibrary>()

    /** Counts metadata reads per document; files whose name contains "untagged" have no tags. */
    private val reads = ConcurrentHashMap<String, AtomicInteger>()
    private val reader = MetadataReader { uri ->
        val documentId = DocumentsContract.getDocumentId(uri)
        reads.getOrPut(documentId) { AtomicInteger() }.incrementAndGet()
        if ("broken" in documentId) return@MetadataReader null
        val name = documentId.substringAfterLast('/').substringBeforeLast('.')
        if ("untagged" in documentId) AudioMetadata(null, null, null, 1000, "audio/mpeg", null)
        else AudioMetadata("Tag $name", "Artista", "Álbum", 180_000, "audio/mpeg", null)
    }

    @After
    fun tearDown() {
        stacks.forEach { it.close() }
        setTree("")
        delay(0)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun setTree(spec: String) {
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
    }

    private fun delay(ms: Long) {
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_DELAY, ms.toString(), null)
    }

    private fun tree(id: String) = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, id)

    private fun newStack(
        permissions: FakePermissions = FakePermissions(),
        databaseName: String? = null,
    ): TestLibrary {
        val stack = if (databaseName == null) TestLibrary(context, permissions, reader)
        else TestLibrary(context, permissions, reader, databaseName)
        stacks += stack
        return stack
    }

    /** Waits until every folder has finished a scan that ended after [since]. */
    private fun awaitScanned(stack: TestLibrary, since: Long, timeoutMs: Long = 15_000, acceptUnavailable: Boolean = true) = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val folders = withContext(Dispatchers.IO) { stack.db.folderDao().getAll() }
                val state = stack.library.state.value
                // A scan that started after [since] has finished (or found the folder unavailable).
                val done = folders.isNotEmpty() && folders.all { f ->
                    (acceptUnavailable && f.lastScanStatus == FolderScanStatus.UNAVAILABLE) || (f.lastCompletedScanAt ?: 0) >= since
                }
                if (done && state.loaded && !state.isScanning && state.tracks.size == visibleTracksInDatabase(stack)) break
                SystemClock.sleep(50)
            }
            // The database is ahead of the StateFlow (Room re-query + mapping on another thread):
            // wait until the state stops changing.
            var last = stack.library.state.value
            while (true) {
                SystemClock.sleep(200)
                val now = stack.library.state.value
                if (now == last) break
                last = now
            }
        } ?: throw AssertionError("Scan did not settle: ${diagnostics(stack, since)}")
        stack.library.state.value
    }

    private fun diagnostics(stack: TestLibrary, since: Long): String {
        val folders = stack.db.folderDao().getAll().joinToString { "${it.id}:${it.lastScanStatus}:${(it.lastCompletedScanAt ?: 0) - since}" }
        val state = stack.library.state.value
        return "since=$since folders=[$folders] loaded=${state.loaded} scanning=${state.isScanning} " +
            "stateTracks=${state.tracks.size} dbVisible=${visibleTracksInDatabase(stack)} " +
            "stateFolders=${state.folders.map { it.status }}"
    }

    /** Distinct tracks of folders whose tracks are shown, straight from the database. */
    private fun visibleTracksInDatabase(stack: TestLibrary): Int =
        stack.db.openHelper.readableDatabase.query(
            """
            SELECT COUNT(DISTINCT ft.trackId) FROM folder_tracks ft JOIN folders f ON f.id = ft.folderId
            WHERE f.lastScanStatus NOT IN ('${FolderScanStatus.UNAVAILABLE}', '${FolderScanStatus.NO_PERMISSION}')
            """.trimIndent()
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun addAndScan(stack: TestLibrary, vararg treeIds: String): LibraryState {
        val since = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { stack.library.load() }
        treeIds.forEach { id -> runBlocking(Dispatchers.Main) { stack.library.addFolder(tree(id)) } }
        // addFolder is asynchronous: wait until every folder is stored before waiting for scans.
        runBlocking {
            withTimeout(5_000) {
                while (withContext(Dispatchers.IO) { stack.db.folderDao().getAll() }.size < treeIds.size) SystemClock.sleep(20)
            }
        }
        return awaitScanned(stack, since)
    }

    private fun rescan(stack: TestLibrary, acceptUnavailable: Boolean = true): LibraryState {
        SystemClock.sleep(5) // distinct timestamps
        val since = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { stack.library.refresh() }
        return awaitScanned(stack, since, acceptUnavailable = acceptUnavailable)
    }

    private fun titles(state: LibraryState) = state.tracks.map { it.title }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    fun newTracksAreIndexedWithMetadata() {
        setTree("lib/a.mp3|100|1\nlib/Sub/b.mp3|200|2\nlib/cover.jpg|5|5")
        val state = addAndScan(newStack(), "lib")
        assertEquals(listOf("Tag a", "Tag b"), titles(state))
        assertEquals("Artista", state.tracks[0].artist)
        assertEquals(180_000L, state.tracks[0].durationMs)
    }

    @Test
    fun libraryPersistsAndIsShownBeforeTheBackgroundScanFinishes() {
        setTree("lib/a.mp3|100|1\nlib/b.mp3|200|2\nlib/c.mp3|300|3")
        val name = "persist-${System.nanoTime()}.db"
        val first = newStack(FakePermissions(), name)
        addAndScan(first, "lib")
        val granted = first.permissions.persistedReadFolders()
        first.close(delete = false) // "close Nezh"
        stacks.remove(first)

        delay(1_000) // every listing now takes 1 s: the background scan is slow
        val reopened = newStack(FakePermissions(granted), name)
        val start = SystemClock.elapsedRealtime()
        runBlocking(Dispatchers.Main) { reopened.library.load() }
        val shown = runBlocking { withTimeout(5_000) { reopened.library.state.first { it.loaded && it.tracks.isNotEmpty() } } }
        val shownAfter = SystemClock.elapsedRealtime() - start
        assertEquals(listOf("Tag a", "Tag b", "Tag c"), titles(shown))
        assertTrue("shown after $shownAfter ms, before the 1 s listing completed", shownAfter < 1_000)
        delay(0)
        awaitScanned(reopened, 0)
        assertTrue("unchanged files are not re-read", reads.values.all { it.get() == 1 })
    }

    @Test
    fun backgroundScanAddsNewFilesAndRereadsOnlyModifiedOnes() {
        setTree("lib/a.mp3|100|1\nlib/b.mp3|200|2")
        val stack = newStack()
        addAndScan(stack, "lib")
        setTree("lib/a.mp3|100|1\nlib/b.mp3|250|9\nlib/c.mp3|300|3")
        val state = rescan(stack)
        assertEquals(listOf("Tag a", "Tag b", "Tag c"), titles(state))
        assertEquals(1, reads["lib/a.mp3"]!!.get()) // unchanged: not re-read
        assertEquals(2, reads["lib/b.mp3"]!!.get()) // size/date changed: re-read
        assertEquals(1, reads["lib/c.mp3"]!!.get()) // new
    }

    @Test
    fun unreliableAttributesAreNotRereadOnEveryScan() {
        setTree("lib/a.mp3|-|-")
        val stack = newStack()
        addAndScan(stack, "lib") // first scan is also the full reconciliation
        rescan(stack)
        assertEquals(1, reads["lib/a.mp3"]!!.get())
    }

    @Test
    fun missingFilesAreRemovedOnlyAfterACompleteListing() {
        setTree("lib/a.mp3|1|1\nlib/Sub/b.mp3|2|2\nlib/Sub/c.mp3|3|3")
        val stack = newStack()
        addAndScan(stack, "lib")
        setTree("lib/a.mp3|1|1\nlib/Sub/c.mp3|3|3")
        val state = rescan(stack)
        assertEquals(listOf("Tag a", "Tag c"), titles(state))
        assertNull(stack.db.trackDao().getTrack(TrackIdentity.of(TestTreeProvider.AUTHORITY, "lib/Sub/b.mp3")))
    }

    @Test
    fun unreadableSubdirectoryKeepsItsRecords() {
        setTree("lib/a.mp3|1|1\nlib/Sub/b.mp3|2|2")
        val stack = newStack()
        addAndScan(stack, "lib")
        setTree("lib/a.mp3|1|1\nlib/Sub/b.mp3|2|2\n!fail:lib/Sub")
        val state = rescan(stack)
        assertEquals(listOf("Tag a", "Tag b"), titles(state))
        assertEquals(1, state.folders.single().skippedDirectories)
        assertEquals(FolderScanStatus.PARTIAL, stack.db.folderDao().getAll().single().lastScanStatus)
    }

    @Test
    fun unavailableFolderKeepsRecordsAndRecovers() {
        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2")
        val stack = newStack()
        addAndScan(stack, "lib")
        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2\n!fail:lib")
        val unavailable = rescan(stack)
        assertEquals(FolderStatus.UNAVAILABLE, unavailable.folders.single().status)
        assertTrue(unavailable.tracks.isEmpty()) // hidden...
        assertEquals(2, stack.db.trackDao().count()) // ...but not deleted
        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2")
        val recovered = rescan(stack, acceptUnavailable = false)
        assertEquals(listOf("Tag a", "Tag b"), titles(recovered))
        assertTrue("no metadata re-read", reads.values.all { it.get() == 1 })
    }

    @Test
    fun revokedPermissionHidesTracksAndRegrantRestoresThem() {
        setTree("lib/a.mp3|1|1")
        val permissions = FakePermissions()
        val stack = newStack(permissions)
        addAndScan(stack, "lib")
        permissions.granted.clear() // revoked outside Nezh
        runBlocking(Dispatchers.Main) { stack.library.refresh() }
        val revoked = runBlocking {
            withTimeout(5_000) { stack.library.state.first { it.folders.singleOrNull()?.status == FolderStatus.NO_PERMISSION } }
        }
        assertTrue(revoked.tracks.isEmpty())
        assertEquals(1, stack.db.trackDao().count())

        val since = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { stack.library.addFolder(tree("lib")) } // re-authorize
        val restored = awaitScanned(stack, since)
        assertEquals(listOf("Tag a"), titles(restored))
        assertEquals(1, reads["lib/a.mp3"]!!.get())
    }

    /** Counts the scans that start (folder status turning to scanning) while [block] runs. */
    private fun countScans(stack: TestLibrary, block: () -> Unit): Int {
        val started = AtomicInteger()
        val watcher = stack.scope.launch {
            var scanning = false
            stack.library.state.collect { state ->
                if (state.isScanning && !scanning) started.incrementAndGet()
                scanning = state.isScanning
            }
        }
        block()
        watcher.cancel()
        return started.get()
    }

    /**
     * A6 (audit): the provider denies access to the folder root (SecurityException) while the
     * persisted grant is still there. Before the fix this rescanned without pause (129 scans in
     * 4 s on the Moto G7 Plus). It must be scanned once and then left alone until a later
     * trigger (refresh, app start, re-authorization).
     */
    @Test
    fun aRootThatDeniesAccessIsScannedOnceNotInALoop() {
        setTree("!deny:lib\nlib/a.mp3|1|1")
        val permissions = FakePermissions()
        val stack = newStack(permissions)
        runBlocking(Dispatchers.Main) { stack.library.load() }
        val scans = countScans(stack) {
            runBlocking(Dispatchers.Main) { stack.library.addFolder(tree("lib")) }
            SystemClock.sleep(4_000)
        }
        val folder = stack.db.folderDao().getAll().single()
        assertEquals("scans started in 4 s (status ${folder.lastScanStatus})", 1, scans)
        assertTrue("the persisted grant is kept", FolderKey(TestTreeProvider.AUTHORITY, "lib") in permissions.granted)
        assertTrue("no grant released", permissions.released.isEmpty())
    }

    @Test
    fun aDeniedRootKeepsTheIndexedTracksAndIsRetriedOnlyOnRefresh() {
        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2")
        val stack = newStack(FakePermissions())
        addAndScan(stack, "lib")
        assertEquals(2, stack.db.trackDao().count())

        setTree("!deny:lib\nlib/a.mp3|1|1\nlib/b.mp3|2|2") // the provider starts refusing the root
        val deniedScans = countScans(stack) {
            runBlocking(Dispatchers.Main) { stack.library.refresh() }
            SystemClock.sleep(3_000)
        }
        assertEquals("one scan per refresh, no retry loop", 1, deniedScans)
        assertEquals("indexed tracks kept", 2, stack.db.trackDao().count())

        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2") // access works again
        val since = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { stack.library.refresh() }
        val recovered = awaitScanned(stack, since)
        assertEquals(listOf("Tag a", "Tag b"), titles(recovered))
        assertEquals(FolderScanStatus.COMPLETE, stack.db.folderDao().getAll().single().lastScanStatus)
    }

    @Test
    fun nestedFoldersAreDeduplicatedAndRemovingOneKeepsTheOther() {
        setTree("lib/a.mp3|1|1\nlib/Rock/r.mp3|2|2")
        val permissions = FakePermissions()
        val stack = newStack(permissions)
        val state = addAndScan(stack, "lib", "lib/Rock")
        assertEquals(listOf("Tag a", "Tag r"), titles(state)) // r appears once
        assertEquals(1, reads["lib/Rock/r.mp3"]!!.get())

        runBlocking(Dispatchers.Main) { stack.library.removeFolder(FolderKey(TestTreeProvider.AUTHORITY, "lib")) }
        val after = runBlocking { withTimeout(5_000) { stack.library.state.first { it.folders.size == 1 } } }
        assertEquals(listOf("Tag r"), titles(after)) // still reachable through lib/Rock
        // The permission is released right after the database update: wait for it.
        runBlocking { withTimeout(5_000) { while (permissions.released.isEmpty()) SystemClock.sleep(20) } }
        assertEquals(listOf(tree("lib")), permissions.released) // only that permission released
        assertTrue(FolderKey(TestTreeProvider.AUTHORITY, "lib/Rock") in permissions.granted)
    }

    @Test
    fun nomediaExcludesDirectoryAndDescendantsAndRemovalRestoresThem() {
        setTree("lib/a.mp3|1|1\nlib/Voice/v.mp3|2|2\nlib/Voice/Old/o.mp3|3|3")
        val stack = newStack()
        addAndScan(stack, "lib")
        setTree("lib/a.mp3|1|1\nlib/Voice/.nomedia|0|1\nlib/Voice/v.mp3|2|2\nlib/Voice/Old/o.mp3|3|3")
        val excluded = rescan(stack)
        assertEquals(listOf("Tag a"), titles(excluded))
        val filter = excluded.queueFilter()
        assertFalse(filter.allows(TrackIdentity.of(TestTreeProvider.AUTHORITY, "lib/Voice/v.mp3"), excluded.folders.single().key))

        setTree("lib/a.mp3|1|1\nlib/Voice/v.mp3|2|2\nlib/Voice/Old/o.mp3|3|3")
        val restored = rescan(stack)
        assertEquals(listOf("Tag a", "Tag o", "Tag v"), titles(restored).sortedBy { it })
    }

    @Test
    fun filesWithoutReadableMetadataStayWithFileNameTitles() {
        setTree("lib/broken song.mp3|1|1\nlib/untagged song.mp3|2|2")
        val stack = newStack()
        val state = addAndScan(stack, "lib")
        assertEquals(listOf("broken song", "untagged song"), titles(state))
        val broken = stack.db.trackDao().getTrack(TrackIdentity.of(TestTreeProvider.AUTHORITY, "lib/broken song.mp3"))!!
        assertEquals(MetadataState.FAILED, broken.metadataState)
    }

    @Test
    fun orderAndSeparatorsFollowEachScan() {
        // Untagged files are titled by file name; tagged ones by their tag ("Tag …").
        setTree("lib/zeta untagged.mp3|1|1\nlib/b.mp3|2|2")
        val stack = newStack()
        val first = addAndScan(stack, "lib")
        assertEquals(listOf("Tag b", "zeta untagged"), titles(first))
        assertEquals(listOf("T", "Z"), first.sections.map { it.label })

        setTree("lib/zeta untagged.mp3|1|1\nlib/b.mp3|2|2\nlib/ábaco untagged.mp3|3|3\nlib/2 untagged.mp3|4|4")
        val after = rescan(stack)
        assertEquals(listOf("ábaco untagged", "Tag b", "zeta untagged", "2 untagged"), titles(after))
        assertEquals(listOf("A", "T", "Z", "#"), after.sections.map { it.label })
        assertEquals(listOf(0, 1, 2, 3), after.sections.map { it.start })
    }

    @Test
    fun queueKeepsLibraryOrderIdsAndDropsRemovedTracks() {
        setTree("lib/a.mp3|1|1\nlib/b.mp3|2|2\nlib/c.mp3|3|3")
        val stack = newStack()
        val state = addAndScan(stack, "lib")
        val items = state.tracks.map { it.toMediaItem() }
        assertEquals(state.tracks.map { it.id }, items.map { it.mediaId })
        assertEquals("Tag b", items[1].mediaMetadata.title)
        assertEquals("lib/b.mp3", DocumentsContract.getDocumentId(items[1].requestMetadata.mediaUri!!))

        setTree("lib/a.mp3|1|1\nlib/c.mp3|3|3")
        val after = rescan(stack)
        val filter = after.queueFilter()
        val folder = after.folders.single().key
        assertTrue(filter.allows(items[0].mediaId, folder))
        assertFalse(filter.allows(items[1].mediaId, folder)) // removed track leaves the queue
        assertTrue(filter.allows(items[2].mediaId, folder))
    }
}
