package com.hachicorp.nezh.library

/** Authority of Android's built-in storage provider (`ExternalStorageProvider`). */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * `ExternalStorageProvider` root that is NOT a volume root: on Android 8.0–10 "home" maps to the
 * primary volume's Documents directory, so "home:" is a normal folder.
 */
private const val HOME_ROOT_ID = "home"

/**
 * Whether [folder] is the root of a whole storage volume in `ExternalStorageProvider`.
 *
 * AOSP (Android 6.0 through 11 and later) builds document ids as `"<rootId>:<relative path>"`,
 * where `rootId` is `"primary"` for shared internal storage or the volume's filesystem UUID
 * (e.g. `"1234-ABCD"`) for SD cards and USB drives, and the relative path of the volume root is
 * empty. So a volume root is exactly a document id whose path part is empty, e.g. `"primary:"` or
 * `"1234-ABCD:"`. The path is parsed structurally (split at the first ':' after the root id, as
 * the provider does), never matched as a substring, so `"primary:Music"` is never rejected.
 *
 * Returns false for every other provider: their id formats are opaque to apps.
 */
internal fun isExternalStorageVolumeRoot(folder: FolderKey): Boolean {
    if (folder.authority != EXTERNAL_STORAGE_AUTHORITY) return false
    val id = folder.treeDocumentId
    // The provider looks for the separator from index 1: the root id is never empty.
    val separator = id.indexOf(':', startIndex = 1)
    if (separator < 0) return false
    val rootId = id.substring(0, separator)
    val path = id.substring(separator + 1)
    // "primary:/" resolves to the same directory as "primary:" in the provider.
    return path.trim('/').isEmpty() && rootId != HOME_ROOT_ID
}
