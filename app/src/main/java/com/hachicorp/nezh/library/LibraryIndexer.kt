package com.hachicorp.nezh.library

import androidx.core.net.toUri
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.hachicorp.nezh.library.artwork.ArtworkCacheManager
import com.hachicorp.nezh.library.db.FolderEntity
import com.hachicorp.nezh.library.db.FolderScanStatus
import com.hachicorp.nezh.library.db.FolderTrackEntity
import com.hachicorp.nezh.library.db.MetadataRequest
import com.hachicorp.nezh.library.db.MetadataState
import com.hachicorp.nezh.library.db.NezhDatabase
import com.hachicorp.nezh.library.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.atomic.AtomicInteger

/** Result of indexing one folder. */
sealed interface IndexOutcome {
    data class Indexed(val stats: IndexStats) : IndexOutcome
    data object NoPermission : IndexOutcome
    data object Unavailable : IndexOutcome
}

/** Counts and durations of one folder index pass (never names or URIs). */
data class IndexStats(
    val queries: Int,
    val files: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val keptUnverified: Int,
    val failedDirectories: Int,
    val walkMs: Long,
    val applyMs: Long,
    val metadataRead: Int,
    val metadataFailed: Int,
    val metadataMs: Long,
)

/**
 * Brings the index of one authorized folder up to date with the folder's contents.
 *
 * 1. Walks the folder (same rules as before: only this tree, `.nomedia`, hidden files, cycles).
 *    New tracks are inserted progressively while walking, so a first scan shows results early;
 *    nothing is removed or rewritten until the walk has finished.
 * 2. Compares the complete walk with the index ([planFolderScan]) and applies the result in one
 *    transaction. Absent tracks are removed only when their directory was listed successfully;
 *    a root failure changes nothing but the folder status.
 * 3. Reads metadata (and covers) only for new or modified files, with bounded concurrency,
 *    writing results in batches.
 *
 * Never modifies, moves or copies the audio files.
 */
class LibraryIndexer(
    private val db: NezhDatabase,
    private val scanner: SafTreeScanner,
    private val metadataReader: MetadataReader,
    private val artwork: ArtworkCacheManager?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val walkParallelism: Int = SafTreeScanner.DEFAULT_PARALLELISM,
    private val metadataParallelism: Int = DEFAULT_METADATA_PARALLELISM,
) {
    private val folderDao = db.folderDao()
    private val trackDao = db.trackDao()

    suspend fun indexFolder(folder: FolderEntity, fullReconcile: Boolean): IndexOutcome = withContext(Dispatchers.IO) {
        val key = FolderKey(folder.authority, folder.treeDocumentId)
        val treeUri = folder.uri.toUri()
        val known = trackDao.knownSources(folder.id)
        val knownIds = known.mapTo(HashSet()) { it.trackId }
        val collator = Collator.getInstance() // used on the walking thread only

        // Progressive insertion of new tracks while walking (insert-only, never deletes).
        var flushed = 0
        var nextFlushAt = SystemClock.elapsedRealtime() + PROGRESS_INTERVAL_MS
        val outcome = scanner.walk(
            treeUri, key,
            checkCancelled = { ensureActive() },
            parallelism = walkParallelism,
            onProgress = { files ->
                val now = SystemClock.elapsedRealtime()
                if (now >= nextFlushAt && files.size > flushed) {
                    val fresh = files.subList(flushed, files.size)
                        .map { it.toSeen(key, treeUri) }
                        .filter { it.trackId !in knownIds }
                    insertNew(folder.id, fresh, collator)
                    flushed = files.size
                    nextFlushAt = SystemClock.elapsedRealtime() + PROGRESS_INTERVAL_MS
                }
            },
        )
        val walk = when (outcome) {
            WalkOutcome.NoPermission -> {
                folderDao.setStatus(folder.id, FolderScanStatus.NO_PERMISSION)
                return@withContext IndexOutcome.NoPermission
            }
            WalkOutcome.Unavailable -> {
                // Root not listable: keep every record (not "empty"), only remember the status.
                folderDao.setStatus(folder.id, FolderScanStatus.UNAVAILABLE)
                return@withContext IndexOutcome.Unavailable
            }
            is WalkOutcome.Success -> outcome
        }

        val applyStart = SystemClock.elapsedRealtime()
        val seen = walk.result.files.map { it.toSeen(key, treeUri) }
        val plan = planFolderScan(
            known = known.map {
                KnownState(
                    it.trackId, it.innerPath, it.documentUri, it.sizeBytes, it.lastModified,
                    metadataRead = it.metadataState != MetadataState.PENDING,
                    metadataSizeBytes = it.metadataSizeBytes,
                    metadataLastModified = it.metadataLastModified,
                )
            },
            seen = seen,
            failedDirectories = walk.result.failedDirectories,
        )
        ensureActive()
        db.runInTransaction {
            insertNewLocked(folder.id, plan.added, collator)
            applyUpdatesLocked(folder.id, plan.updated, collator)
            plan.metadataStale.toList().chunked(CHUNK).forEach { trackDao.markMetadataPending(it) }
            if (plan.removed.isNotEmpty()) {
                plan.removed.chunked(CHUNK).forEach { trackDao.deleteSources(folder.id, it) }
                trackDao.deleteOrphans()
            }
            val status = if (walk.result.failedDirectories.isEmpty()) FolderScanStatus.COMPLETE else FolderScanStatus.PARTIAL
            folderDao.setScanCompleted(folder.id, status, clock())
        }
        val applyMs = SystemClock.elapsedRealtime() - applyStart

        val metadataStart = SystemClock.elapsedRealtime()
        val (read, failed) = readMetadata(folder.id, fullReconcile)
        if (fullReconcile) folderDao.setFullReconcile(folder.id, clock())

        IndexOutcome.Indexed(
            IndexStats(
                queries = walk.result.directoriesListed,
                files = seen.size,
                added = plan.added.size,
                updated = plan.updated.size,
                removed = plan.removed.size,
                keptUnverified = plan.keptUnverified,
                failedDirectories = walk.result.failedDirectories.size,
                walkMs = walk.walkMs,
                applyMs = applyMs,
                metadataRead = read,
                metadataFailed = failed,
                metadataMs = SystemClock.elapsedRealtime() - metadataStart,
            )
        )
    }

    /**
     * Re-creates the cached cover of [trackId] from its audio file after an eviction. Returns the
     * cover hash, or null when the file has no readable cover. If the file's cover changed since it
     * was indexed, the track is linked to the new one.
     */
    fun regenerateArtwork(trackId: String): String? {
        val cache = artwork ?: return null
        val uri = trackDao.documentUriOf(trackId) ?: return null
        val picture = metadataReader.read(uri.toUri())?.picture ?: return null
        val hash = cache.put(picture) ?: return null
        trackDao.setArtworkHash(trackId, hash)
        return hash
    }

    private fun insertNew(folderId: String, files: List<SeenFile>, collator: Collator) {
        if (files.isEmpty()) return
        db.runInTransaction { insertNewLocked(folderId, files, collator) }
    }

    private fun insertNewLocked(folderId: String, files: List<SeenFile>, collator: Collator) {
        files.chunked(CHUNK).forEach { chunk ->
            trackDao.insertNewTracks(chunk.map { it.toTrackEntity() })
            trackDao.insertNewSources(chunk.map { it.toSource(folderId, collator) })
        }
    }

    /** Updates provider attributes and location, keeping the metadata already read. */
    private fun applyUpdatesLocked(folderId: String, files: List<SeenFile>, collator: Collator) {
        files.chunked(CHUNK).forEach { chunk ->
            val existing = trackDao.getTracks(chunk.map { it.trackId }).associateBy { it.id }
            trackDao.upsertTracks(chunk.mapNotNull { file ->
                existing[file.trackId]?.copy(
                    displayName = file.displayName,
                    mimeType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    lastModified = file.lastModified,
                )
            })
            trackDao.upsertSources(chunk.map { it.toSource(folderId, collator) })
        }
    }

    private class MetadataResult(val request: MetadataRequest, val metadata: AudioMetadata?, val artworkHash: String?)

    /** Reads pending metadata with bounded concurrency; returns (read, failed). */
    private suspend fun readMetadata(folderId: String, fullReconcile: Boolean): Pair<Int, Int> = coroutineScope {
        val requests = trackDao.metadataRequests(folderId, fullReconcile)
        if (requests.isEmpty()) return@coroutineScope 0 to 0
        val results = Channel<MetadataResult>(capacity = 64)
        val next = AtomicInteger(0)
        val workers = List(metadataParallelism) {
            launch(Dispatchers.IO) {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= requests.size) break
                    val request = requests[index]
                    val metadata = metadataReader.read(request.documentUri.toUri())
                    val hash = metadata?.picture?.let { picture -> artwork?.put(picture) }
                    results.send(MetadataResult(request, metadata, hash))
                }
            }
        }
        launch {
            workers.forEach { it.join() }
            results.close()
        }
        var read = 0
        var failed = 0
        val batch = ArrayList<MetadataResult>()
        var lastWrite = SystemClock.elapsedRealtime()
        for (result in results) {
            if (result.metadata == null) failed++ else read++
            batch += result
            if (batch.size >= METADATA_BATCH || SystemClock.elapsedRealtime() - lastWrite >= PROGRESS_INTERVAL_MS) {
                writeMetadata(batch)
                batch.clear()
                lastWrite = SystemClock.elapsedRealtime()
            }
        }
        writeMetadata(batch)
        if (failed > 0) Log.i(TAG, "Metadata unreadable for $failed files (kept with file-name titles)")
        read to failed
    }

    private fun writeMetadata(batch: List<MetadataResult>) {
        if (batch.isEmpty()) return
        db.runInTransaction {
            for (result in batch) {
                val m = result.metadata
                trackDao.updateMetadata(
                    id = result.request.trackId,
                    title = m?.title,
                    artist = m?.artist,
                    album = m?.album,
                    durationMs = m?.durationMs,
                    format = m?.format,
                    state = if (m == null) MetadataState.FAILED else MetadataState.READ,
                    sizeBytes = result.request.sizeBytes,
                    lastModified = result.request.lastModified,
                    artworkHash = result.artworkHash,
                )
            }
        }
    }

    private fun FoundAudio.toSeen(key: FolderKey, treeUri: Uri) = SeenFile(
        trackId = TrackIdentity.of(key.authority, documentId),
        documentId = documentId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        innerPath = innerPath,
        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
    )

    private fun SeenFile.toTrackEntity() = TrackEntity(
        id = trackId,
        authority = TrackIdentity.parse(trackId)!!.first,
        documentId = documentId,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
    )

    private fun SeenFile.toSource(folderId: String, collator: Collator) = FolderTrackEntity(
        folderId = folderId,
        trackId = trackId,
        innerPath = innerPath,
        documentUri = documentUri,
        sortKey = collator.getCollationKey(innerPath).toByteArray(),
    )

    companion object {
        private const val TAG = "LibraryIndexer"
        /** Metadata reads in flight. Each holds a file descriptor and some native memory. */
        const val DEFAULT_METADATA_PARALLELISM = 4
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val METADATA_BATCH = 100
        /** Below SQLite's 999 bound-variable limit on old Android versions. */
        private const val CHUNK = 400
    }
}
