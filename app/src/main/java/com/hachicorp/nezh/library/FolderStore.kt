package com.hachicorp.nezh.library

import androidx.core.content.edit
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A folder the user authorized, as persisted. */
data class StoredFolder(val uri: Uri, val name: String)

/**
 * Legacy (pre-index) storage of the authorized folders. Only read once, to migrate the list into
 * the library index ([com.hachicorp.nezh.library.db.NezhDatabase]), then cleared.
 *
 * Persists the user's authorized folders (tree URIs) and their order in SharedPreferences.
 * Does blocking disk I/O: call from a background thread.
 */
class FolderStore(context: Context, prefsName: String = PREFS_NAME) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun read(): List<StoredFolder> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                StoredFolder(Uri.parse(item.getString(FIELD_URI)), item.getString(FIELD_NAME))
            }
        } catch (e: JSONException) {
            // Do not log the raw value: it contains folder URIs.
            Log.e(TAG, "Stored folder list is unreadable; starting with an empty library", e)
            emptyList()
        }
    }

    /** Returns false if the write could not be committed to disk. */
    fun write(folders: List<StoredFolder>): Boolean {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(
                JSONObject()
                    .put(FIELD_URI, folder.uri.toString())
                    .put(FIELD_NAME, folder.name)
            )
        }
        val committed = prefs.edit().putString(KEY_FOLDERS, array.toString()).commit()
        if (!committed) Log.e(TAG, "Could not save the folder list (${folders.size} folders)")
        return committed
    }

    /** Removes the stored list (after it was migrated to the library index). */
    fun clear() {
        prefs.edit(commit = true) { remove(KEY_FOLDERS) }
    }

    private companion object {
        const val TAG = "FolderStore"
        const val PREFS_NAME = "library"
        const val KEY_FOLDERS = "folders"
        const val FIELD_URI = "uri"
        const val FIELD_NAME = "name"
    }
}
