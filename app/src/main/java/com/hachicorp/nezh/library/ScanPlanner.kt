package com.hachicorp.nezh.library

/** A track source seen by the current walk of one folder. */
internal data class SeenFile(
    val trackId: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val innerPath: String,
    val documentUri: String,
)

/** What the index knew about a source of the folder before the walk. */
internal data class KnownState(
    val trackId: String,
    val innerPath: String,
    val documentUri: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    /** True when the stored metadata was read (successfully or not) for some size/date. */
    val metadataRead: Boolean,
    val metadataSizeBytes: Long?,
    val metadataLastModified: Long?,
)

internal data class FolderScanPlan(
    /** Sources new to this folder (the track may already exist through another folder). */
    val added: List<SeenFile>,
    /** Known sources whose path, URI, size or date changed: their rows must be updated. */
    val updated: List<SeenFile>,
    /** Tracks whose stored metadata no longer matches the file and must be re-read. */
    val metadataStale: Set<String>,
    /** Sources confirmed absent: listed directories that no longer contain them. */
    val removed: List<String>,
    /** Sources absent from the walk but kept because their directory could not be listed. */
    val keptUnverified: Int,
)

/**
 * Compares a completed walk of one folder with what the index knew about it.
 *
 * Safety rules:
 * - Only call this when the folder root was listed; a root failure must not produce a plan.
 * - A known source missing from the walk is removed only if no ancestor directory failed to list
 *   ([failedDirectories] are "<dirs>/" paths relative to the folder). Otherwise it is kept.
 * - Directories excluded by `.nomedia` were listed successfully, so their tracks are removed.
 * - Changes are detected from provider size and modification date. When either is unknown, the
 *   file is treated as unchanged (no re-read on every launch); the periodic full reconciliation
 *   re-reads such tracks.
 */
internal fun planFolderScan(
    known: List<KnownState>,
    seen: List<SeenFile>,
    failedDirectories: List<String>,
): FolderScanPlan {
    val knownById = known.associateBy { it.trackId }
    val seenIds = HashSet<String>(seen.size * 2)
    val added = ArrayList<SeenFile>()
    val updated = ArrayList<SeenFile>()
    val stale = HashSet<String>()

    for (file in seen) {
        if (!seenIds.add(file.trackId)) continue // defensive: the walker never yields duplicates
        val before = knownById[file.trackId]
        if (before == null) {
            added += file
            continue
        }
        val moved = before.innerPath != file.innerPath || before.documentUri != file.documentUri
        val attributesChanged = before.sizeBytes != file.sizeBytes || before.lastModified != file.lastModified
        if (moved || attributesChanged) updated += file
        if (before.metadataRead && isReliablyModified(before, file)) stale += file.trackId
    }

    val removed = ArrayList<String>()
    var kept = 0
    for (source in known) {
        if (source.trackId in seenIds) continue
        if (failedDirectories.any { source.innerPath.startsWith(it) }) kept++ else removed += source.trackId
    }
    return FolderScanPlan(added, updated, stale, removed, kept)
}

/** True only when both size and date are known now and differ from those the metadata was read for. */
private fun isReliablyModified(before: KnownState, now: SeenFile): Boolean {
    val size = now.sizeBytes ?: return false
    val date = now.lastModified ?: return false
    return size != before.metadataSizeBytes || date != before.metadataLastModified
}
