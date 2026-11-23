package com.hachicorp.nezh.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.hachicorp.nezh.library.artwork.ArtworkCacheManager
import com.hachicorp.nezh.library.artwork.ArtworkStore
import com.hachicorp.nezh.library.db.NezhDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * Builds a complete library stack for instrumented tests: real Room database, real scanner and
 * indexer; permissions and metadata reading can be replaced. Everything lives in a unique
 * directory/database name and is deleted by [close].
 */
internal class TestLibrary(
    val context: Context,
    val permissions: FolderPermissions,
    val metadata: MetadataReader = MetadataReader { null },
    /** Name of an on-disk database to reopen; a new unique one by default. */
    val databaseName: String = "test-${UUID.randomUUID()}.db",
    val artworkBudget: Long = ArtworkCacheManager.DEFAULT_BUDGET_BYTES,
    /** Pre-index folder list to migrate (use a test-only preferences name). */
    val legacyStore: FolderStore? = null,
) {
    val directory = File(context.filesDir, "test-artwork-$databaseName")
    val db: NezhDatabase = Room.databaseBuilder(context, NezhDatabase::class.java, databaseName).build()
    // Same shape as the production scope (NezhApplication), including the error handler.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("TestLibrary", "Library coroutine failed", e) }
    )
    val store = ArtworkStore(directory)
    val artwork = ArtworkCacheManager(store, db.artworkDao(), db.trackDao(), budgetBytes = artworkBudget)
    val indexer = LibraryIndexer(db, SafTreeScanner(context.contentResolver), metadata, artwork)
    val library = MusicLibrary(db, indexer, artwork, directory, permissions, legacyStore = legacyStore, scope = scope)

    /** Closes this instance (as if Nezh were closed); the database file is kept unless [delete]. */
    fun close(delete: Boolean = true) {
        // Wait for in-flight library work before closing the database it uses.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        if (delete) {
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }
}

/** Permissions for test-provider folders: an in-memory set the test controls. */
internal class FakePermissions(initial: Set<FolderKey> = emptySet()) : FolderPermissions {
    val granted: MutableSet<FolderKey> = java.util.Collections.synchronizedSet(initial.toMutableSet())
    val released = java.util.Collections.synchronizedList(ArrayList<Uri>())

    override fun persistedReadFolders(): Set<FolderKey> = synchronized(granted) { granted.toSet() }

    override fun takeReadPermission(treeUri: Uri): Boolean {
        FolderKey.of(treeUri)?.let { granted += it }
        return true
    }

    override fun releaseReadPermission(treeUri: Uri) {
        released += treeUri
        FolderKey.of(treeUri)?.let { granted -= it }
    }

    override fun displayName(treeUri: Uri, key: FolderKey): String = key.treeDocumentId.substringAfterLast('/')
}
