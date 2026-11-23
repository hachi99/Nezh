package com.hachicorp.nezh.library.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A folder the user authorized through SAF. The persisted SAF permission, not this row, is what
 * grants access; this row only remembers the folder and its last scan.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    /** "<authority>::<tree document id>" (see FolderKey). */
    @PrimaryKey val id: String,
    val authority: String,
    val treeDocumentId: String,
    /** Tree URI as returned by the picker; used for queries and to release the permission. */
    val uri: String,
    val name: String,
    /** Order chosen by the user (order of addition). */
    val position: Int,
    /** Last known outcome: see [FolderScanStatus]. */
    val lastScanStatus: String = FolderScanStatus.NEVER_SCANNED,
    /** End of the last scan that completed without a root failure, or null. */
    val lastCompletedScanAt: Long? = null,
    /** Last time every track's metadata was re-validated regardless of size/date. */
    val lastFullReconcileAt: Long? = null,
)

object FolderScanStatus {
    const val NEVER_SCANNED = "NEVER_SCANNED"
    const val COMPLETE = "COMPLETE"
    /** Finished, but some subdirectories could not be listed (their records were kept). */
    const val PARTIAL = "PARTIAL"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val NO_PERMISSION = "NO_PERMISSION"
}

/**
 * A track, identified by its SAF document: "<authority>::<document id>". The same document reached
 * through nested authorized folders is one track (see [FolderTrackEntity] for its sources).
 * This id is also the Media3 media id.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("artist"), Index("album"), Index("artworkHash")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val authority: String,
    val documentId: String,
    val displayName: String,
    /** MIME type reported by the provider. */
    val mimeType: String?,
    /** Provider attributes; null when the provider does not report them. */
    val sizeBytes: Long?,
    val lastModified: Long?,
    // Metadata read from the file. Null = unknown (the UI falls back to the file name).
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Container MIME type detected while reading the file. */
    val format: String? = null,
    /** See [MetadataState]. */
    val metadataState: Int = MetadataState.PENDING,
    /** size/lastModified the metadata was read for; a mismatch means the file changed. */
    val metadataSizeBytes: Long? = null,
    val metadataLastModified: Long? = null,
    /** SHA-256 of the embedded picture's bytes, or null if the file has none. */
    val artworkHash: String? = null,
)

object MetadataState {
    const val PENDING = 0
    const val READ = 1
    /** The file could not be read; the track stays in the library with its file-name title. */
    const val FAILED = 2
}

/** One source of a track: the authorized folder it was found in, and where. */
@Entity(
    tableName = "folder_tracks",
    primaryKeys = ["folderId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId"), Index(value = ["folderId", "sortKey"])],
)
data class FolderTrackEntity(
    val folderId: String,
    val trackId: String,
    /** Path inside the folder: "<subdirectories>/<file name>". */
    val innerPath: String,
    /** Document URI under this folder's tree: what playback opens. */
    val documentUri: String,
    /** Locale collation key of [innerPath]; ORDER BY gives the same order as the previous sort. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val sortKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is FolderTrackEntity && folderId == other.folderId && trackId == other.trackId &&
            innerPath == other.innerPath && documentUri == other.documentUri &&
            sortKey.contentEquals(other.sortKey)

    override fun hashCode(): Int = 31 * folderId.hashCode() + trackId.hashCode()
}

/** A cached cover image file managed by ArtworkCacheManager. Files live outside the database. */
@Entity(tableName = "artwork", indices = [Index("lastAccessedAt")])
data class ArtworkEntity(
    /** SHA-256 (hex) of the original embedded image bytes: identical covers share one file. */
    @PrimaryKey val hash: String,
    val fileName: String,
    /** Real size of the file on disk. */
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val lastAccessedAt: Long,
)
