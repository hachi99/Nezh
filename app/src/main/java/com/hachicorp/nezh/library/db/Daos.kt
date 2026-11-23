package com.hachicorp.nezh.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Non-Flow DAO methods are blocking on purpose: they are only called from background threads
// (Room rejects main-thread access) and are grouped with NezhDatabase.runInTransaction.

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY position")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY position")
    fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun get(id: String): FolderEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM folders")
    fun nextPosition(): Int

    @Upsert
    fun upsert(folder: FolderEntity)

    /** Cascades to folder_tracks; orphaned tracks must then be removed with [TrackDao.deleteOrphans]. */
    @Query("DELETE FROM folders WHERE id = :id")
    fun delete(id: String)

    @Query("UPDATE folders SET lastScanStatus = :status WHERE id = :id")
    fun setStatus(id: String, status: String)

    @Query("UPDATE folders SET lastScanStatus = :status, lastCompletedScanAt = :completedAt WHERE id = :id")
    fun setScanCompleted(id: String, status: String, completedAt: Long)

    @Query("UPDATE folders SET lastFullReconcileAt = :at WHERE id = :id")
    fun setFullReconcile(id: String, at: Long)
}

/** One row of the library list: a track as found through one folder. */
data class LibraryRow(
    val id: String,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val artworkFile: String?,
    val folderId: String,
    val innerPath: String,
    val documentUri: String,
)

/** What a scan needs to know about a source it may have seen before. */
data class KnownSource(
    val trackId: String,
    val innerPath: String,
    val documentUri: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val metadataState: Int,
    val metadataSizeBytes: Long?,
    val metadataLastModified: Long?,
)

/** A track whose metadata must be (re)read, with a URI it can be opened through. */
data class MetadataRequest(
    val trackId: String,
    val documentUri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
)

@Dao
interface TrackDao {
    /**
     * Every source of every track, in display order (folder order, then collation order).
     * The same track can appear once per folder; the repository keeps the first.
     */
    @Query(
        """
        SELECT t.id, t.displayName, t.title, t.artist, t.album, t.durationMs,
               a.fileName AS artworkFile, ft.folderId, ft.innerPath, ft.documentUri
        FROM folder_tracks ft
        JOIN folders f ON f.id = ft.folderId
        JOIN tracks t ON t.id = ft.trackId
        LEFT JOIN artwork a ON a.hash = t.artworkHash
        ORDER BY f.position, ft.sortKey
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    @Query(
        """
        SELECT ft.trackId, ft.innerPath, ft.documentUri, t.sizeBytes, t.lastModified,
               t.metadataState, t.metadataSizeBytes, t.metadataLastModified
        FROM folder_tracks ft JOIN tracks t ON t.id = ft.trackId
        WHERE ft.folderId = :folderId
        """
    )
    fun knownSources(folderId: String): List<KnownSource>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    fun getTracks(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getTrack(id: String): TrackEntity?

    /** Inserts tracks that do not exist yet; existing rows (and their metadata) are untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNewTracks(tracks: List<TrackEntity>)

    @Upsert
    fun upsertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNewSources(sources: List<FolderTrackEntity>)

    @Upsert
    fun upsertSources(sources: List<FolderTrackEntity>)

    @Query("DELETE FROM folder_tracks WHERE folderId = :folderId AND trackId IN (:trackIds)")
    fun deleteSources(folderId: String, trackIds: List<String>)

    /** Tracks no longer reachable through any authorized folder. */
    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT trackId FROM folder_tracks)")
    fun deleteOrphans(): Int

    @Query("UPDATE tracks SET metadataState = ${MetadataState.PENDING} WHERE id IN (:ids)")
    fun markMetadataPending(ids: List<String>)

    /**
     * Tracks of [folderId] whose metadata must be read: pending ones, plus (during a full
     * reconciliation) tracks whose size/date cannot be trusted and tracks that failed before.
     */
    @Query(
        """
        SELECT t.id AS trackId, ft.documentUri, t.displayName, t.sizeBytes, t.lastModified
        FROM folder_tracks ft JOIN tracks t ON t.id = ft.trackId
        WHERE ft.folderId = :folderId AND (
            t.metadataState = ${MetadataState.PENDING}
            OR (:fullReconcile AND (t.sizeBytes IS NULL OR t.lastModified IS NULL
                OR t.metadataState = ${MetadataState.FAILED}))
        )
        """
    )
    fun metadataRequests(folderId: String, fullReconcile: Boolean): List<MetadataRequest>

    @Query(
        """
        UPDATE tracks SET title = :title, artist = :artist, album = :album, durationMs = :durationMs,
            format = :format, metadataState = :state, metadataSizeBytes = :sizeBytes,
            metadataLastModified = :lastModified, artworkHash = :artworkHash
        WHERE id = :id
        """
    )
    fun updateMetadata(
        id: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?,
        format: String?,
        state: Int,
        sizeBytes: Long?,
        lastModified: Long?,
        artworkHash: String?,
    )

    @Query("UPDATE tracks SET artworkHash = :hash WHERE id = :id")
    fun setArtworkHash(id: String, hash: String)

    @Query("SELECT DISTINCT artworkHash FROM tracks WHERE artworkHash IS NOT NULL")
    fun referencedArtworkHashes(): List<String>

    /** First source (in folder order) through which [trackId] can be opened. */
    @Query(
        """
        SELECT ft.documentUri FROM folder_tracks ft JOIN folders f ON f.id = ft.folderId
        WHERE ft.trackId = :trackId ORDER BY f.position LIMIT 1
        """
    )
    fun documentUriOf(trackId: String): String?

    @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Int
}

@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artwork WHERE hash = :hash")
    fun get(hash: String): ArtworkEntity?

    @Query("SELECT * FROM artwork")
    fun getAll(): List<ArtworkEntity>

    @Upsert
    fun upsert(artwork: ArtworkEntity)

    @Query("UPDATE artwork SET lastAccessedAt = :at WHERE hash = :hash")
    fun touch(hash: String, at: Long)

    @Query("DELETE FROM artwork WHERE hash IN (:hashes)")
    fun delete(hashes: List<String>)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM artwork")
    fun totalSize(): Long
}
