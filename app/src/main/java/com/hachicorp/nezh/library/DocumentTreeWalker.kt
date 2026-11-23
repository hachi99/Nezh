package com.hachicorp.nezh.library

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A child row returned by a SAF provider. */
internal data class DocumentEntry(
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    /** Provider-reported size and modification time; null when not reported. */
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
)

/** An audio document found under the tree. [relativeDir] is "" or ends with "/". */
internal data class FoundAudio(
    val documentId: String,
    val displayName: String,
    val relativeDir: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
) {
    /** Path inside the authorized folder: "<subdirectories>/<file name>". */
    val innerPath: String get() = "$relativeDir$displayName"
}

internal data class WalkResult(
    val files: List<FoundAudio>,
    /** Subdirectories that could not be listed and were skipped. */
    val skippedDirectories: Int,
    /** Directories (including the root) excluded because they contain a `.nomedia` file. */
    val nomediaDirectories: Int = 0,
    /** Provider listings performed (one query per directory). */
    val directoriesListed: Int = 0,
    /** Child rows returned by the provider, all types. */
    val entriesSeen: Int = 0,
    /**
     * Paths ("<dirs>/", relative to the root) of subdirectories that could not be listed. Records
     * under them must be kept: their absence in this walk proves nothing.
     */
    val failedDirectories: List<String> = emptyList(),
)

/** Android convention: a directory containing this file must not be indexed as media. */
internal const val NOMEDIA_FILE_NAME = ".nomedia"

/** Thrown by a child lister when a directory cannot be listed. */
internal class DirectoryListingException(cause: Throwable) : Exception(cause)

/**
 * Walks a document tree starting at [rootDocumentId], visiting only descendants of the root.
 *
 * - A failure listing the root propagates as [DirectoryListingException] (the folder is unusable).
 * - A failure listing a subdirectory skips that subdirectory and is counted.
 * - Each directory is visited at most once, so provider-level cycles cannot loop.
 * - A directory containing `.nomedia` (including the root) is excluded entirely: none of its
 *   files are listed and none of its subdirectories are visited.
 * - Hidden (dot-prefixed) files and directories are ignored.
 * - [checkCancelled] is called before each batch of directories so long scans can be cancelled.
 *
 * Up to [parallelism] directories are listed concurrently (provider queries are mostly waiting on
 * the provider, measured at ~30–100 ms each through ExternalStorageProvider). Listings are always
 * processed in a deterministic order on the calling thread, so the rules above are unchanged.
 * [onProgress] receives the files found so far after each batch; it must not keep the list.
 *
 * Every scan is a fresh walk, so adding or removing `.nomedia` takes effect on the next scan.
 */
internal fun walkDocumentTree(
    rootDocumentId: String,
    listChildren: (documentId: String) -> List<DocumentEntry>,
    checkCancelled: () -> Unit = {},
    parallelism: Int = 1,
    onProgress: ((List<FoundAudio>) -> Unit)? = null,
): WalkResult {
    require(parallelism >= 1)
    val files = mutableListOf<FoundAudio>()
    val visited = HashSet<String>()
    val pending = ArrayDeque<Pair<String, String>>()
    pending.addLast(rootDocumentId to "")
    var skipped = 0
    var nomedia = 0
    var listed = 0
    var entries = 0
    val failed = ArrayList<String>()
    val executor = if (parallelism > 1) Executors.newFixedThreadPool(parallelism) else null

    try {
        while (pending.isNotEmpty()) {
            checkCancelled()
            val batch = ArrayList<Pair<String, String>>(parallelism)
            while (batch.size < parallelism && pending.isNotEmpty()) {
                val next = pending.removeLast()
                if (visited.add(next.first)) batch += next
            }
            if (batch.isEmpty()) continue
            listed += batch.size

            val listings = listBatch(batch.map { it.first }, listChildren, executor)
            for ((index, listing) in listings.withIndex()) {
                val (directoryId, relativeDir) = batch[index]
                val children = listing.getOrElse { error ->
                    if (error !is DirectoryListingException) throw error
                    if (directoryId == rootDocumentId) throw error
                    skipped++
                    failed += relativeDir
                    null
                } ?: continue

                entries += children.size
                // Checked before using any child: a .nomedia directory contributes nothing.
                if (children.any { it.displayName.equals(NOMEDIA_FILE_NAME, ignoreCase = true) }) {
                    nomedia++
                    continue
                }

                for (child in children) {
                    if (child.displayName.startsWith('.')) continue
                    if (child.mimeType == DIRECTORY_MIME_TYPE) {
                        pending.addLast(child.documentId to "$relativeDir${child.displayName}/")
                    } else if (isAudioDocument(child.mimeType, child.displayName)) {
                        files += FoundAudio(
                            child.documentId, child.displayName, relativeDir,
                            child.mimeType, child.sizeBytes, child.lastModified,
                        )
                    }
                }
            }
            onProgress?.invoke(files)
        }
    } finally {
        executor?.shutdownNow()
    }
    return WalkResult(files, skipped, nomedia, listed, entries, failed)
}

/** Lists [directoryIds], concurrently when an executor is given; results keep the input order. */
private fun listBatch(
    directoryIds: List<String>,
    listChildren: (String) -> List<DocumentEntry>,
    executor: ExecutorService?,
): List<Result<List<DocumentEntry>>> {
    if (executor == null || directoryIds.size == 1) {
        return directoryIds.map { id -> runCatchingListing { listChildren(id) } }
    }
    val futures = directoryIds.map { id -> executor.submit(Callable { listChildren(id) }) }
    return futures.map { future ->
        runCatchingListing {
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}

/** Captures listing failures; anything else (bugs, cancellation) is rethrown as-is. */
private inline fun runCatchingListing(block: () -> List<DocumentEntry>): Result<List<DocumentEntry>> =
    try {
        Result.success(block())
    } catch (e: DirectoryListingException) {
        Result.failure(e)
    }

/**
 * Concatenates per-folder lists in folder order, keeping only the first occurrence of each id.
 * Needed when the user selects nested folders (e.g. "Music" and "Music/Rock").
 */
internal fun <T> mergeUnique(perFolder: List<List<T>>, id: (T) -> String): List<T> {
    val seen = HashSet<String>()
    val result = ArrayList<T>(perFolder.sumOf { it.size })
    for (list in perFolder) {
        for (item in list) {
            if (seen.add(id(item))) result += item
        }
    }
    return result
}
