package com.hachicorp.nezh.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Persistent library index. It stores what Nezh knows about the user's authorized folders and
 * tracks; it never stores audio and never grants access (SAF permissions remain the authority).
 * The playback queue is intentionally not stored here.
 */
@Database(
    entities = [FolderEntity::class, TrackEntity::class, FolderTrackEntity::class, ArtworkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NezhDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun trackDao(): TrackDao
    abstract fun artworkDao(): ArtworkDao

    companion object {
        const val NAME = "nezh-library.db"

        fun open(context: Context, name: String? = NAME): NezhDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, NezhDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, NezhDatabase::class.java, name)
            }
            return builder.build()
        }
    }
}
