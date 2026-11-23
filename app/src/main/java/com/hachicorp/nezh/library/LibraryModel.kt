package com.hachicorp.nezh.library

import androidx.core.net.toUri
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Identity of an authorized folder: provider authority + tree document id. Comparing keys instead
 * of URI strings avoids mismatches caused by different URI encodings of the same tree.
 */
data class FolderKey(val authority: String, val treeDocumentId: String) {
    /** Primary key of the folder in the library index. */
    val id: String get() = "$authority::$treeDocumentId"

    companion object {
        /** Key of a tree URI, or of a document URI built under a tree. Null for other URIs. */
        fun of(uri: Uri): FolderKey? {
            val authority = uri.authority ?: return null
            return try {
                FolderKey(authority, DocumentsContract.getTreeDocumentId(uri))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * A library track. Plain strings only: equality stays cheap (StateFlow compares whole libraries on
 * every update; comparing android.net.Uri fields measured 146 ms for 10,000 tracks) and the Uri is
 * only built when the track is played.
 */
data class Track(
    /** [TrackIdentity]: authority + document id. Also the Media3 media id. */
    val id: String,
    /** Document URI under the authorized tree it was found through; what playback opens. */
    val documentUri: String,
    /** Title to display: the tag title, or the file-name title when there is none. */
    val title: String,
    /** "<folder name>/<subdirectories>/<file name>", used for display and ordering. */
    val relativePath: String,
    val folder: FolderKey,
    /** Title derived from the file name (fallback). */
    val fileTitle: String = title,
    /** Title tag, when the file has one and it was read. */
    val tagTitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** file:// URI of the cached cover, when one is stored. */
    val artworkUri: String? = null,
) {
    val uri: Uri get() = documentUri.toUri()
}

enum class FolderStatus {
    SCANNING,
    READY,
    /** Nezh no longer holds the persisted read permission. */
    NO_PERMISSION,
    /** Permission held, but the provider could not list the folder (removed card, deleted folder…). */
    UNAVAILABLE,
}

data class LibraryFolder(
    val key: FolderKey,
    val uri: Uri,
    val name: String,
    val status: FolderStatus,
    val trackCount: Int = 0,
    val skippedDirectories: Int = 0,
)

/** One-shot messages for the user about folder operations. */
sealed interface LibraryNotice {
    data object FolderAlreadyAdded : LibraryNotice
    data object StorageRootNotAllowed : LibraryNotice
    data object InvalidFolder : LibraryNotice
    /** Android did not grant (or keep) a persisted permission for the selected folder. */
    data object PermissionNotPersisted : LibraryNotice
    /** Previously authorized folders that no longer have permission. */
    data class PermissionsLost(val count: Int) : LibraryNotice
    data object SaveFailed : LibraryNotice
}

data class LibraryState(
    /** False until the stored folders have been read and their permissions checked. */
    val loaded: Boolean = false,
    val folders: List<LibraryFolder> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val notice: LibraryNotice? = null,
) {
    val isScanning: Boolean get() = folders.any { it.status == FolderStatus.SCANNING }

    /** Folders whose permission is held; tracks from other folders cannot be played. */
    val permittedFolders: Set<FolderKey>
        get() = folders.filter { it.status != FolderStatus.NO_PERMISSION }.mapTo(HashSet()) { it.key }

    fun queueFilter(): QueueFilter = QueueFilter(
        permittedFolders = permittedFolders,
        scannedFolders = folders.filter { it.status == FolderStatus.READY }.mapTo(HashSet()) { it.key },
        libraryTrackIds = tracks.mapTo(HashSet()) { it.id },
    )
}

/**
 * Decides which queued tracks may stay in the queue:
 * - never a track whose folder was removed or lost permission;
 * - never a track from a successfully scanned folder that the latest scan no longer found
 *   (e.g. its directory now contains `.nomedia`).
 * Tracks of folders that are still scanning or temporarily unavailable are kept.
 */
data class QueueFilter(
    val permittedFolders: Set<FolderKey>,
    val scannedFolders: Set<FolderKey>,
    val libraryTrackIds: Set<String>,
) {
    fun allows(trackId: String, folder: FolderKey?): Boolean {
        if (folder == null || folder !in permittedFolders) return false
        return folder !in scannedFolders || trackId in libraryTrackIds
    }
}
