package com.hachicorp.nezh.library.artwork

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Cover image files, stored in a private persistent directory (not the cache dir: Android may
 * clear that at any time). Plain java.io only, so it is unit-testable.
 *
 * Safety:
 * - Writes are atomic: data goes to a temporary file, is synced and verified, then renamed into
 *   place. A crash leaves at most a temporary file, removed by [deleteTemporaryFiles].
 * - A file is never deleted while it is being written or read through [read]
 *   ([delete] returns false instead).
 */
class ArtworkStore(val directory: File) {

    private val tempDirectory = File(directory, TEMP_DIR)
    private val busy = HashMap<String, Int>() // file name -> active readers/writers

    init {
        tempDirectory.mkdirs()
    }

    fun fileFor(fileName: String): File = File(directory, fileName)

    /**
     * Atomically writes [bytes] as [fileName]. Returns the real size on disk.
     * @throws IOException if the data could not be written or verified (nothing is left behind).
     */
    fun write(fileName: String, bytes: ByteArray): Long {
        require(isValidName(fileName)) { "Invalid artwork file name" }
        acquire(fileName)
        val temp = File(tempDirectory, "$fileName.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (temp.length() != bytes.size.toLong()) throw IOException("Incomplete artwork write")
            val target = fileFor(fileName)
            // Same name = same source image; replace a stale copy (rename does not overwrite everywhere).
            if (target.exists() && !target.delete()) throw IOException("Cannot replace artwork file")
            if (!temp.renameTo(target)) throw IOException("Cannot move artwork into place")
            return target.length()
        } finally {
            if (temp.exists()) temp.delete()
            release(fileName)
        }
    }

    /** Runs [block] with the file protected from deletion; null if it does not exist. */
    fun <T> read(fileName: String, block: (File) -> T): T? {
        acquire(fileName)
        try {
            val file = fileFor(fileName)
            return if (file.isFile) block(file) else null
        } finally {
            release(fileName)
        }
    }

    /** Deletes [fileName] unless it is being read or written. Returns true if it no longer exists. */
    fun delete(fileName: String): Boolean = synchronized(busy) {
        if ((busy[fileName] ?: 0) > 0) return false
        val file = fileFor(fileName)
        !file.exists() || file.delete()
    }

    fun isBusy(fileName: String): Boolean = synchronized(busy) { (busy[fileName] ?: 0) > 0 }

    fun exists(fileName: String): Boolean = fileFor(fileName).isFile

    fun sizeOf(fileName: String): Long = fileFor(fileName).length()

    /** Names of the finished artwork files currently on disk. */
    fun listFileNames(): List<String> =
        directory.listFiles { file -> file.isFile }?.map { it.name }.orEmpty()

    /** Removes leftovers of interrupted writes. Returns how many were removed. */
    fun deleteTemporaryFiles(): Int {
        var removed = 0
        tempDirectory.listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".tmp")
            if (!isBusy(name) && file.delete()) removed++
        }
        return removed
    }

    private fun acquire(fileName: String) = synchronized(busy) { busy[fileName] = (busy[fileName] ?: 0) + 1 }

    private fun release(fileName: String) = synchronized(busy) {
        val count = (busy[fileName] ?: 1) - 1
        if (count <= 0) busy.remove(fileName) else busy[fileName] = count
    }

    companion object {
        private const val TEMP_DIR = "tmp"

        /** Hash-based names only: hex characters plus one known extension. */
        fun isValidName(name: String): Boolean = name.matches(Regex("[0-9a-f]{16,64}\\.(jpg|png)"))
    }
}

/** An entry considered for eviction. */
data class CachedArtwork(val hash: String, val sizeBytes: Long, val lastAccessedAt: Long)

/**
 * Least-recently-used eviction: returns the hashes to delete, oldest access first, until the
 * total fits in [budgetBytes]. [protected] entries (in use, being read/written) are never chosen,
 * so the result may still exceed the budget when everything left is protected.
 */
fun planEviction(entries: List<CachedArtwork>, budgetBytes: Long, protected: Set<String>): List<String> {
    var total = entries.sumOf { it.sizeBytes }
    if (total <= budgetBytes) return emptyList()
    val victims = ArrayList<String>()
    for (entry in entries.sortedBy { it.lastAccessedAt }) {
        if (total <= budgetBytes) break
        if (entry.hash in protected) continue
        victims += entry.hash
        total -= entry.sizeBytes
    }
    return victims
}
