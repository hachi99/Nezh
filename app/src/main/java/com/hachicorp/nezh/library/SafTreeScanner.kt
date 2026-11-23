package com.hachicorp.nezh.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Trace
import android.provider.DocumentsContract
import android.util.Log
import java.text.Collator

/** Result of scanning one authorized folder. */
sealed interface ScanOutcome {
    data class Success(
        val tracks: List<Track>,
        val skippedDirectories: Int,
        val stats: ScanStats = ScanStats(),
    ) : ScanOutcome
    data object NoPermission : ScanOutcome
    data object Unavailable : ScanOutcome
}

/** Result of walking one authorized folder (no track list is built). */
internal sealed interface WalkOutcome {
    data class Success(val result: WalkResult, val walkMs: Long) : WalkOutcome
    data object NoPermission : WalkOutcome
    data object Unavailable : WalkOutcome
}

/** Cost of one folder scan. Counts and durations only: nothing that identifies files. */
data class ScanStats(
    /** SAF child queries issued (one per directory listed). */
    val queries: Int = 0,
    /** Rows returned by the provider across all queries. */
    val entries: Int = 0,
    /** Time spent walking the tree (provider queries + cursor reads). */
    val walkMs: Long = 0,
    /** Time spent building and sorting the track list. */
    val buildMs: Long = 0,
)

/**
 * SAF access for authorized folders. Every method does blocking IPC: call from a background thread.
 * Logs never include URIs or file names (they can reveal personal paths).
 */
class SafTreeScanner(private val resolver: ContentResolver) {

    /**
     * Walks [treeUri] (only that folder and its subdirectories) and returns the audio documents
     * with their provider attributes. A root failure is reported, never turned into an empty
     * result. [onProgress] receives the files found so far after each batch of directories.
     */
    internal fun walk(
        treeUri: Uri,
        key: FolderKey,
        checkCancelled: () -> Unit,
        parallelism: Int = DEFAULT_PARALLELISM,
        onProgress: ((List<FoundAudio>) -> Unit)? = null,
    ): WalkOutcome {
        val start = SystemClock.elapsedRealtime()
        val result = try {
            traced("Nezh.walkTree") {
                walkDocumentTree(
                    rootDocumentId = key.treeDocumentId,
                    listChildren = { documentId -> listChildren(treeUri, documentId) },
                    checkCancelled = checkCancelled,
                    parallelism = parallelism,
                    onProgress = onProgress,
                )
            }
        } catch (e: DirectoryListingException) {
            val cause = e.cause
            Log.w(TAG, "Cannot list folder root: ${cause?.javaClass?.simpleName}")
            return if (cause is SecurityException) WalkOutcome.NoPermission else WalkOutcome.Unavailable
        }
        if (result.skippedDirectories > 0) Log.w(TAG, "Skipped ${result.skippedDirectories} unreadable subdirectories")
        if (result.nomediaDirectories > 0) Log.i(TAG, "Excluded ${result.nomediaDirectories} directories containing .nomedia")
        return WalkOutcome.Success(result, SystemClock.elapsedRealtime() - start)
    }

    /**
     * Lists audio files under [treeUri] and its subdirectories only, as an in-memory sorted list.
     * Used by the scan benchmark to compare with the pre-index implementation; the app indexes
     * through [walk] and LibraryIndexer.
     *
     * [onPartial] (optional) receives sorted snapshots of the tracks found so far while the walk is
     * still running, at most every [PARTIAL_MIN_INTERVAL_MS] and never more often than twice the
     * cost of producing a snapshot, so early results can be shown without slowing the scan down.
     */
    fun scan(
        treeUri: Uri,
        key: FolderKey,
        folderName: String,
        checkCancelled: () -> Unit,
        parallelism: Int = DEFAULT_PARALLELISM,
        onPartial: ((List<Track>) -> Unit)? = null,
    ): ScanOutcome {
        val walkStart = SystemClock.elapsedRealtime()
        val collator = Collator.getInstance()
        val built = ArrayList<Track>()
        fun buildUpTo(files: List<FoundAudio>) {
            for (i in built.size until files.size) built += files[i].toTrack(treeUri, key, folderName)
        }
        var nextPartialAt = walkStart + PARTIAL_MIN_INTERVAL_MS
        val progress: ((List<FoundAudio>) -> Unit)? = onPartial?.let { publish ->
            { files ->
                val now = SystemClock.elapsedRealtime()
                if (now >= nextPartialAt && files.size > built.size) {
                    buildUpTo(files)
                    publish(built.sortedWith(compareBy(collator) { it.relativePath }))
                    val cost = SystemClock.elapsedRealtime() - now
                    nextPartialAt = SystemClock.elapsedRealtime() + maxOf(PARTIAL_MIN_INTERVAL_MS, 2 * cost)
                }
            }
        }

        Trace.beginSection("Nezh.walkTree")
        val result = try {
            walkDocumentTree(
                rootDocumentId = key.treeDocumentId,
                listChildren = { documentId -> listChildren(treeUri, documentId) },
                checkCancelled = checkCancelled,
                parallelism = parallelism,
                onProgress = progress,
            )
        } catch (e: DirectoryListingException) {
            val cause = e.cause
            Log.w(TAG, "Cannot list folder root: ${cause?.javaClass?.simpleName}")
            return if (cause is SecurityException) ScanOutcome.NoPermission else ScanOutcome.Unavailable
        } finally {
            Trace.endSection()
        }
        val walkMs = SystemClock.elapsedRealtime() - walkStart
        if (result.skippedDirectories > 0) {
            Log.w(TAG, "Skipped ${result.skippedDirectories} unreadable subdirectories")
        }
        if (result.nomediaDirectories > 0) {
            Log.i(TAG, "Excluded ${result.nomediaDirectories} directories containing .nomedia")
        }
        val buildStart = SystemClock.elapsedRealtime()
        val tracks = traced("Nezh.buildTracks") {
            buildUpTo(result.files)
            built.sortedWith(compareBy(collator) { it.relativePath })
        }
        val stats = ScanStats(
            queries = result.directoriesListed,
            entries = result.entriesSeen,
            walkMs = walkMs,
            buildMs = SystemClock.elapsedRealtime() - buildStart,
        )
        return ScanOutcome.Success(tracks, result.skippedDirectories, stats)
    }

    private inline fun <T> traced(section: String, block: () -> T): T {
        Trace.beginSection(section)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    private fun FoundAudio.toTrack(treeUri: Uri, key: FolderKey, folderName: String) = Track(
        id = TrackIdentity.of(key.authority, documentId),
        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
        title = titleFromDisplayName(displayName),
        relativePath = "$folderName/$relativeDir$displayName",
        folder = key,
    )

    /**
     * True if [key] is the root of a whole device storage volume, which Nezh refuses because it
     * would scan the entire storage. Android 11+ already blocks this in the picker; this check
     * covers API 23–29.
     *
     * Only `ExternalStorageProvider` exposes device storage volumes, and its root list cannot be
     * queried by apps (it throws SecurityException), so its documented id format is used instead.
     * Other providers' ids are opaque, so their folders are never rejected here.
     */
    fun isStorageRoot(key: FolderKey): Boolean = isExternalStorageVolumeRoot(key)

    /** Display name of the folder itself, with a fallback derived from the document id. */
    fun displayName(treeUri: Uri, key: FolderKey): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, key.treeDocumentId)
        val name = try {
            resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read folder name: ${e.javaClass.simpleName}")
            null
        }
        return name?.takeIf { it.isNotBlank() }
            ?: key.treeDocumentId.substringAfterLast(':').substringAfterLast('/').ifBlank { key.treeDocumentId }
    }

    /**
     * Takes the persisted read permission for [treeUri] and verifies Android actually kept it.
     * Returns false if Android refused it (e.g. the per-app limit of persisted grants).
     */
    fun takeReadPermission(treeUri: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Persisted permission refused: ${e.javaClass.simpleName}")
            return false
        }
        return FolderKey.of(treeUri) in persistedReadFolders()
    }

    /** Releases only the persisted permission of [treeUri]. */
    fun releaseReadPermission(treeUri: Uri) {
        try {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already revoked or never held: nothing left to release.
            Log.i(TAG, "No persisted permission to release: ${e.javaClass.simpleName}")
        }
    }

    /** Folders for which Nezh currently holds a persisted read permission. */
    fun persistedReadFolders(): Set<FolderKey> =
        resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapNotNullTo(HashSet()) { FolderKey.of(it.uri) }

    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<DocumentEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        try {
            val cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
                ?: throw DirectoryListingException(IllegalStateException("Provider returned no cursor"))
            return cursor.use {
                val entries = ArrayList<DocumentEntry>(it.count.coerceAtLeast(0))
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    entries += DocumentEntry(
                        documentId = id,
                        displayName = it.getString(1).orEmpty(),
                        mimeType = it.getString(2),
                        // Unknown values stay null: change detection is conservative about them.
                        sizeBytes = if (it.isNull(3)) null else it.getLong(3).takeIf { size -> size >= 0 },
                        lastModified = if (it.isNull(4)) null else it.getLong(4).takeIf { time -> time > 0 },
                    )
                }
                entries
            }
        } catch (e: RuntimeException) {
            throw DirectoryListingException(e)
        }
    }

    companion object {
        private const val TAG = "SafTreeScanner"

        /**
         * Concurrent SAF queries per folder scan. Measured on ExternalStorageProvider (API 37 emulator,
         * 10,000 tracks): 1 → ~22 s, 2 → ~17 s, 4 → ~14–18 s, 8 → ~13.5 s, 16 → ~14 s.
         */
        const val DEFAULT_PARALLELISM = 8

        /** Minimum time between partial results while scanning. */
        const val PARTIAL_MIN_INTERVAL_MS = 1_000L

        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
