package com.hachicorp.nezh.library

import androidx.core.net.toUri
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.hachicorp.nezh.library.artwork.ArtworkCacheManager
import com.hachicorp.nezh.library.db.FolderEntity
import com.hachicorp.nezh.library.db.FolderScanStatus
import com.hachicorp.nezh.library.db.LibraryRow
import com.hachicorp.nezh.library.db.NezhDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's music library, backed by the persistent index ([NezhDatabase]).
 *
 * - Opening shows the last known library from the index immediately; a background scan then
 *   brings it up to date (one folder at a time: scans never run concurrently).
 * - Only folders the user picked are scanned. Persisted SAF permissions are the only authority:
 *   folders without permission stay listed (for re-authorization or removal) but their tracks
 *   are hidden; their records are kept until the folder is removed.
 * - Scan orchestration state is confined to the main thread ([scope] uses a main dispatcher);
 *   database and provider work runs on background dispatchers.
 */
class MusicLibrary(
    private val db: NezhDatabase,
    private val indexer: LibraryIndexer,
    private val artwork: ArtworkCacheManager,
    private val artworkDirectory: File,
    private val permissions: FolderPermissions,
    private val legacyStore: FolderStore?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val folderDao = db.folderDao()
    private val trackDao = db.trackDao()

    private data class Runtime(
        /** Null until the persisted permissions have been read. */
        val permitted: Set<FolderKey>? = null,
        val scanning: Set<String> = emptySet(),
        val skippedDirectories: Map<String, Int> = emptyMap(),
    )

    private val runtime = MutableStateFlow(Runtime())
    private val notice = MutableStateFlow<LibraryNotice?>(null)

    private val createdAt = SystemClock.elapsedRealtime()
    private var firstRowsLogged = false

    val state: StateFlow<LibraryState> =
        combine(
            folderDao.observeAll(),
            trackDao.observeLibrary().onEach { rows ->
                if (!firstRowsLogged) {
                    firstRowsLogged = true
                    Log.i(PERF_TAG, "index query rows=${rows.size} afterMs=${SystemClock.elapsedRealtime() - createdAt}")
                }
            },
            runtime,
            notice,
        ) { folders, rows, rt, n ->
            buildState(folders, rows, rt, n)
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, LibraryState())

    private var loadStarted = false
    /**
     * Serializes folder additions/removals (fair, so in call order): two quick additions read
     * the next position concurrently and could get the same or swapped order (found by a test).
     */
    private val folderMutation = Mutex()
    private val pendingScans = LinkedHashSet<String>()
    private var scanLoop: Job? = null
    private var scanningFolder: String? = null

    /** Shows the indexed library, checks permissions and starts a background scan. Idempotent. */
    fun load() {
        if (loadStarted) return
        loadStarted = true
        val start = SystemClock.elapsedRealtime()
        scope.launch {
            val first = state.first { it.loaded }
            Log.i(PERF_TAG, "library shown tracks=${first.tracks.size} afterMs=${SystemClock.elapsedRealtime() - start} sinceCreatedMs=${SystemClock.elapsedRealtime() - createdAt}")
        }
        scope.launch {
            // Migration and permissions in one background step: the stored library is shown as
            // soon as permissions are known, without waiting for the main thread in between.
            // Under the folder lock: a folder added at the same moment cannot interleave with the
            // migration (which would otherwise see a non-empty index and skip the old list).
            val regained = folderMutation.withLock {
                withContext(Dispatchers.IO) {
                    migrateLegacyFolders()
                    reconcilePermissionsBlocking(notifyLosses = false)
                }
            }
            requestScan(regained + permittedFolderIds())
            // Cover maintenance does not delay showing the library.
            withContext(Dispatchers.IO) {
                val report = artwork.cleanup()
                Log.i(TAG, "Artwork cleanup: $report")
            }
        }
    }

    /** Re-checks permissions and rescans every folder that still has permission. */
    fun refresh() {
        if (runtime.value.permitted == null) return
        scope.launch {
            reconcilePermissions(notifyLosses = false)
            requestScan(permittedFolderIds())
        }
    }

    /**
     * Adds a folder returned by the SAF tree picker, or restores access to a folder whose
     * permission was lost. The transient grant from the picker becomes a persisted one.
     */
    fun addFolder(treeUri: Uri) {
        scope.launch { folderMutation.withLock { addFolderLocked(treeUri) } }
    }

    private suspend fun addFolderLocked(treeUri: Uri) {
        val key = FolderKey.of(treeUri) ?: run {
            notice.value = LibraryNotice.InvalidFolder
            return
        }
        val existing = withContext(Dispatchers.IO) { folderDao.get(key.id) }
        if (existing != null && key in runtime.value.permitted.orEmpty()) {
            notice.value = LibraryNotice.FolderAlreadyAdded
            return
        }
        if (isExternalStorageVolumeRoot(key)) {
            // No permission is taken: the picker's transient grant simply expires.
            notice.value = LibraryNotice.StorageRootNotAllowed
            return
        }
        val granted = withContext(Dispatchers.IO) { permissions.takeReadPermission(treeUri) }
        if (!granted) {
            notice.value = LibraryNotice.PermissionNotPersisted
            return
        }
        withContext(Dispatchers.IO) {
            val folder = existing?.copy(uri = treeUri.toString()) ?: FolderEntity(
                id = key.id,
                authority = key.authority,
                treeDocumentId = key.treeDocumentId,
                uri = treeUri.toString(),
                name = permissions.displayName(treeUri, key),
                position = folderDao.nextPosition(),
            )
            folderDao.upsert(folder)
        }
        // Taking a new grant can make Android drop older ones; surface that instead of hiding it.
        reconcilePermissions(notifyLosses = true)
        requestScan(listOf(key.id))
    }

    /** Removes a folder from the library and releases only that folder's permission. */
    fun removeFolder(key: FolderKey) {
        scope.launch { folderMutation.withLock { removeFolderLocked(key) } }
    }

    private suspend fun removeFolderLocked(key: FolderKey) {
        val id = key.id
        pendingScans.remove(id)
        if (scanningFolder == id) {
            scanLoop?.cancelAndJoin()
            scanLoop = null
            scanningFolder = null
            runtime.update { it.copy(scanning = it.scanning - id) }
        }
        val folder = withContext(Dispatchers.IO) { folderDao.get(id) } ?: return
        withContext(Dispatchers.IO) {
            db.runInTransaction {
                folderDao.delete(id) // cascades to this folder's sources
                trackDao.deleteOrphans() // tracks still reachable through other folders stay
            }
            artwork.removeUnreferenced()
            permissions.releaseReadPermission(folder.uri.toUri())
        }
        runtime.update { it.copy(skippedDirectories = it.skippedDirectories - id) }
        startScanLoopIfNeeded()
    }

    fun dismissNotice() {
        notice.value = null
    }

    /**
     * Called by the playback service when the current track changes: protects its cover from
     * eviction while it is in use, and regenerates the cover if it was evicted.
     */
    fun onPlaybackTrackChanged(trackId: String?) {
        scope.launch(Dispatchers.IO) {
            val hash = trackId?.let { trackDao.getTrack(it)?.artworkHash }
            artwork.setInUse(hash)
            if (trackId != null && hash != null && !artwork.isStored(hash)) {
                val regenerated = indexer.regenerateArtwork(trackId)
                if (regenerated != null) artwork.setInUse(regenerated)
            }
        }
    }

    private fun requestScan(folderIds: Collection<String>) {
        pendingScans += folderIds
        startScanLoopIfNeeded()
    }

    private fun startScanLoopIfNeeded() {
        if (scanLoop?.isActive == true || pendingScans.isEmpty()) return
        scanLoop = scope.launch {
            while (pendingScans.isNotEmpty()) {
                val id = pendingScans.first()
                pendingScans.remove(id)
                scanningFolder = id
                try {
                    scanFolder(id)
                } finally {
                    scanningFolder = null
                }
            }
        }
    }

    private suspend fun scanFolder(id: String) {
        val folder = withContext(Dispatchers.IO) { folderDao.get(id) } ?: return
        val key = FolderKey(folder.authority, folder.treeDocumentId)
        if (key !in runtime.value.permitted.orEmpty()) return
        runtime.update { it.copy(scanning = it.scanning + id) }
        try {
            val fullReconcile = clock() - (folder.lastFullReconcileAt ?: 0L) >= FULL_RECONCILE_INTERVAL_MS
            val start = SystemClock.elapsedRealtime()
            when (val outcome = indexer.indexFolder(folder, fullReconcile)) {
                is IndexOutcome.Indexed -> {
                    val s = outcome.stats
                    Log.i(
                        PERF_TAG,
                        "index folder#${folder.position} files=${s.files} queries=${s.queries} added=${s.added} " +
                            "updated=${s.updated} removed=${s.removed} kept=${s.keptUnverified} " +
                            "walkMs=${s.walkMs} applyMs=${s.applyMs} metadataRead=${s.metadataRead} " +
                            "metadataFailed=${s.metadataFailed} metadataMs=${s.metadataMs} full=$fullReconcile " +
                            "totalMs=${SystemClock.elapsedRealtime() - start}",
                    )
                    runtime.update { it.copy(skippedDirectories = it.skippedDirectories + (id to s.failedDirectories)) }
                }
                IndexOutcome.NoPermission -> reconcilePermissions(notifyLosses = false)
                IndexOutcome.Unavailable -> Log.i(PERF_TAG, "index folder#${folder.position} unavailable")
            }
        } finally {
            runtime.update { it.copy(scanning = it.scanning - id) }
        }
    }

    /**
     * Reads the persisted permissions. Folders that lost theirs are marked (never removed);
     * folders that regained it are scanned again.
     */
    private suspend fun reconcilePermissions(notifyLosses: Boolean) {
        val regained = withContext(Dispatchers.IO) { reconcilePermissionsBlocking(notifyLosses) }
        if (regained.isNotEmpty()) requestScan(regained)
    }

    /**
     * Runs entirely on a background thread and publishes the result directly (StateFlows are
     * thread-safe): on a cold start the main thread is busy with the first composition, and
     * hopping back to it between steps delayed showing the stored library by ~1 s (measured).
     * Returns the folders that regained their permission.
     */
    private fun reconcilePermissionsBlocking(notifyLosses: Boolean): List<String> = synchronized(reconcileLock) {
        // Atomic read-and-publish: two concurrent reconciliations (e.g. startup and a folder just
        // added) could otherwise publish an older permission set last (found by a test).
        val permitted = permissions.persistedReadFolders()
        val folders = folderDao.getAll()
        val previous = runtime.value.permitted
        var lost = 0
        val regained = ArrayList<String>()
        for (folder in folders) {
            val key = FolderKey(folder.authority, folder.treeDocumentId)
            val has = key in permitted
            if (!has && folder.lastScanStatus != FolderScanStatus.NO_PERMISSION) {
                folderDao.setStatus(folder.id, FolderScanStatus.NO_PERMISSION)
                if (previous == null || key in previous) lost++
            } else if (has && folder.lastScanStatus == FolderScanStatus.NO_PERMISSION && (previous == null || key !in previous)) {
                // Regained = the grant was missing from what we knew and is back. NO_PERMISSION with
                // a grant we already had means the provider refused the scan (SecurityException at
                // the root): rescanning now would fail the same way, without pause. That folder is
                // retried on the next refresh, app start or re-authorization instead.
                folderDao.setStatus(folder.id, FolderScanStatus.NEVER_SCANNED)
                regained += folder.id
            }
        }
        if (previous == null) Log.i(PERF_TAG, "permissions known afterMs=${SystemClock.elapsedRealtime() - createdAt}")
        runtime.update { it.copy(permitted = permitted) }
        if (lost > 0) {
            Log.w(TAG, "$lost folders lost their persisted permission")
            if (notifyLosses) notice.value = LibraryNotice.PermissionsLost(lost)
        }
        regained
    }

    private val reconcileLock = Any()

    private suspend fun permittedFolderIds(): List<String> {
        val permitted = runtime.value.permitted.orEmpty()
        return withContext(Dispatchers.IO) { folderDao.getAll() }
            .filter { FolderKey(it.authority, it.treeDocumentId) in permitted }
            .map { it.id }
    }

    /**
     * Moves the folder list kept in SharedPreferences before the index existed into the index.
     *
     * - Idempotent: folders already in the index (or repeated in the old list) are not imported
     *   again, so an interruption between the commit and [FolderStore.clear] causes no duplicates.
     * - The old list is cleared only after the import transaction committed. If anything fails,
     *   it is kept and the migration runs again on the next launch; the library still loads.
     * - Order is kept (old list order, after any folder already present). SAF permissions are
     *   neither taken nor released: they belong to the folders' URIs, which are kept unchanged.
     */
    private fun migrateLegacyFolders() {
        val legacy = legacyStore ?: return
        val stored = legacy.read() // an unreadable list reads as empty: nothing is cleared then
        if (stored.isEmpty()) return
        try {
            var imported = 0
            db.runInTransaction {
                val present = folderDao.getAll().mapTo(HashSet()) { it.id }
                var position = folderDao.nextPosition()
                for (saved in stored) {
                    val key = FolderKey.of(saved.uri) ?: continue
                    if (!present.add(key.id)) continue
                    folderDao.upsert(
                        FolderEntity(key.id, key.authority, key.treeDocumentId, saved.uri.toString(), saved.name, position++)
                    )
                    imported++
                }
            }
            legacy.clear()
            Log.i(TAG, "Migrated $imported of ${stored.size} stored folders to the library index")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Folder migration failed; the original list is kept for the next launch", e)
        }
    }

    private fun buildState(
        folders: List<FolderEntity>,
        rows: List<LibraryRow>,
        rt: Runtime,
        notice: LibraryNotice?,
    ): LibraryState {
        val permitted = rt.permitted ?: return LibraryState(loaded = false, notice = notice)
        val counts = HashMap<String, Int>()
        rows.forEach { counts[it.folderId] = (counts[it.folderId] ?: 0) + 1 }
        val libraryFolders = folders.map { folder ->
            val key = FolderKey(folder.authority, folder.treeDocumentId)
            val status = when {
                key !in permitted -> FolderStatus.NO_PERMISSION
                folder.id in rt.scanning -> FolderStatus.SCANNING
                folder.lastScanStatus == FolderScanStatus.UNAVAILABLE -> FolderStatus.UNAVAILABLE
                else -> FolderStatus.READY
            }
            LibraryFolder(
                key = key,
                uri = folder.uri.toUri(),
                name = folder.name,
                status = status,
                trackCount = counts[folder.id] ?: 0,
                skippedDirectories = rt.skippedDirectories[folder.id] ?: 0,
            )
        }
        // Tracks are shown for folders that can be read (ready, or being rescanned); records of
        // unavailable or unauthorized folders are kept but hidden.
        val visible = HashMap<String, Pair<FolderEntity, LibraryFolder>>()
        folders.zip(libraryFolders).forEach { (entity, folder) ->
            if (folder.status == FolderStatus.READY || folder.status == FolderStatus.SCANNING) {
                visible[entity.id] = entity to folder
            }
        }
        val seen = HashSet<String>(rows.size * 2)
        val tracks = ArrayList<Track>(rows.size)
        for (row in rows) {
            val (entity, folder) = visible[row.folderId] ?: continue
            if (!seen.add(row.id)) continue // nested folders: first folder in order wins
            val fileTitle = titleFromDisplayName(row.displayName)
            tracks += Track(
                id = row.id,
                documentUri = row.documentUri,
                title = row.title ?: fileTitle,
                relativePath = "${entity.name}/${row.innerPath}",
                folder = folder.key,
                fileTitle = fileTitle,
                tagTitle = row.title,
                artist = row.artist,
                album = row.album,
                durationMs = row.durationMs,
                artworkUri = row.artworkFile?.let { Uri.fromFile(File(artworkDirectory, it)).toString() },
            )
        }
        val sortStart = SystemClock.elapsedRealtime()
        val (sorted, sections) = TitleOrder.sort(tracks)
        if (sorted.size >= SORT_LOG_THRESHOLD) {
            Log.i(PERF_TAG, "library sort tracks=${sorted.size} ms=${SystemClock.elapsedRealtime() - sortStart}")
        }
        return LibraryState(loaded = true, folders = libraryFolders, tracks = sorted, sections = sections, notice = notice)
    }

    private companion object {
        const val TAG = "MusicLibrary"
        /** Performance measurements: counts and durations only, never names or URIs. */
        const val PERF_TAG = "NezhPerf"
        /** Tracks whose size/date the provider does not report are re-read this often. */
        const val FULL_RECONCILE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        /** Sorting time is logged for large libraries only. */
        const val SORT_LOG_THRESHOLD = 1000
    }
}
