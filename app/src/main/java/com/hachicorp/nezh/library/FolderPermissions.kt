package com.hachicorp.nezh.library

import android.net.Uri

/**
 * Access to the persisted SAF permissions, the only authority for reading authorized folders.
 * An interface so library tests can use folders of a test provider. Blocking: background thread.
 */
interface FolderPermissions {
    /** Folders for which Nezh currently holds a persisted read permission. */
    fun persistedReadFolders(): Set<FolderKey>

    /** Takes and verifies the persisted read permission of [treeUri]. */
    fun takeReadPermission(treeUri: Uri): Boolean

    /** Releases only the permission of [treeUri]. */
    fun releaseReadPermission(treeUri: Uri)

    fun displayName(treeUri: Uri, key: FolderKey): String
}

class SafFolderPermissions(private val scanner: SafTreeScanner) : FolderPermissions {
    override fun persistedReadFolders() = scanner.persistedReadFolders()
    override fun takeReadPermission(treeUri: Uri) = scanner.takeReadPermission(treeUri)
    override fun releaseReadPermission(treeUri: Uri) = scanner.releaseReadPermission(treeUri)
    override fun displayName(treeUri: Uri, key: FolderKey) = scanner.displayName(treeUri, key)
}
