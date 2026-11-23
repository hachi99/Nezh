package com.hachicorp.nezh

import android.app.Application
import android.util.Log
import com.hachicorp.nezh.library.FolderStore
import com.hachicorp.nezh.library.LibraryIndexer
import com.hachicorp.nezh.library.MusicLibrary
import com.hachicorp.nezh.library.PlatformMetadataReader
import com.hachicorp.nezh.library.SafFolderPermissions
import com.hachicorp.nezh.library.SafTreeScanner
import com.hachicorp.nezh.library.artwork.ArtworkCacheManager
import com.hachicorp.nezh.library.artwork.ArtworkStore
import com.hachicorp.nezh.library.db.NezhDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Creates the app-wide objects on first use (manual wiring, no DI framework). */
class NezhApplication : Application() {

    private val scope: CoroutineScope by lazy {
        val errors = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Unexpected library error", throwable)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    }

    val database: NezhDatabase by lazy { NezhDatabase.open(this) }

    /** Private persistent storage (not cacheDir, which Android may clear at any time). */
    private val artworkStore: ArtworkStore by lazy { ArtworkStore(File(filesDir, "artwork")) }

    val artworkCache: ArtworkCacheManager by lazy {
        ArtworkCacheManager(artworkStore, database.artworkDao(), database.trackDao())
    }

    /** App-wide library state; survives Activity recreation. */
    val library: MusicLibrary by lazy {
        val scanner = SafTreeScanner(contentResolver)
        MusicLibrary(
            db = database,
            indexer = LibraryIndexer(database, scanner, PlatformMetadataReader(this), artworkCache),
            artwork = artworkCache,
            artworkDirectory = artworkStore.directory,
            permissions = SafFolderPermissions(scanner),
            legacyStore = FolderStore(this),
            scope = scope,
        )
    }

    private companion object {
        const val TAG = "NezhApplication"
    }
}
