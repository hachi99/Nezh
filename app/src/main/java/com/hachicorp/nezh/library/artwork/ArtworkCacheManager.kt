package com.hachicorp.nezh.library.artwork

import android.util.Log
import com.hachicorp.nezh.library.db.ArtworkDao
import com.hachicorp.nezh.library.db.ArtworkEntity
import com.hachicorp.nezh.library.db.TrackDao
import java.io.IOException
import java.security.MessageDigest

/**
 * Manages the persistent cover cache: deduplication by image hash, a size budget enforced by
 * least-recently-used eviction, protection of covers in use, and cleanup of orphans.
 *
 * Rules:
 * - One file per distinct source image (SHA-256 of the embedded bytes), shared by every track.
 * - The budget counts real file sizes. A cover that alone exceeds the budget is not stored.
 * - Eviction (budget pressure) may remove covers that tracks still reference: the tracks stay in
 *   the library and the cover can be regenerated from the audio file. Reference cleanup only
 *   removes covers that no track references.
 * - Covers in use (the playing track) and files being read or written are never removed.
 *
 * Blocking (database + files): call from a background thread. Thread-safe.
 */
class ArtworkCacheManager(
    private val store: ArtworkStore,
    private val artworkDao: ArtworkDao,
    private val trackDao: TrackDao,
    private val encoder: ArtworkEncoder = ArtworkEncoder(),
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val inUse = HashSet<String>()
    /** Hashes being encoded right now, so concurrent tracks with the same cover encode it once. */
    private val inProgress = HashSet<String>()

    /** Hash identifying an embedded image; equal images get equal hashes. */
    fun hashOf(original: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }

    /**
     * Caches the cover [original] (embedded image bytes) if needed and returns its hash, which the
     * track keeps even when the image could not be stored. Returns null if it is not an image.
     */
    fun put(original: ByteArray): String? {
        val hash = hashOf(original)
        synchronized(lock) {
            val existing = artworkDao.get(hash)
            if (existing != null && store.exists(existing.fileName)) {
                artworkDao.touch(hash, clock())
                return hash
            }
            if (!inProgress.add(hash)) return hash
        }
        try {
            return encodeAndStore(hash, original)
        } finally {
            synchronized(lock) { inProgress.remove(hash) }
        }
    }

    private fun encodeAndStore(hash: String, original: ByteArray): String? {
        // Decoding/encoding happens outside the lock: it is the expensive part.
        val encoded = encoder.encode(original) ?: return null
        if (encoded.bytes.size > budgetBytes) {
            Log.i(TAG, "Cover larger than the whole cache budget; not stored")
            return hash
        }
        synchronized(lock) {
            val fileName = "$hash.${encoded.extension}"
            val size = try {
                store.write(fileName, encoded.bytes)
            } catch (e: IOException) {
                Log.w(TAG, "Cannot store cover: ${e.javaClass.simpleName}")
                return hash
            }
            artworkDao.upsert(ArtworkEntity(hash, fileName, size, encoded.width, encoded.height, clock()))
            enforceBudgetLocked()
        }
        return hash
    }

    /**
     * Marks the cover of the playing track ([hash], or none) as in use and refreshes its access
     * time. The access time is only written when it is older than [TOUCH_INTERVAL_MS]: every
     * write to the artwork table makes the library re-read and re-sort every track, and doing that
     * on each track change was measurable work for nothing (eviction order within an hour does not
     * matter, and the cover in use is protected in memory anyway).
     */
    fun setInUse(hash: String?) {
        synchronized(lock) {
            inUse.clear()
            if (hash != null) {
                inUse += hash
                val now = clock()
                val row = artworkDao.get(hash)
                if (row != null && now - row.lastAccessedAt >= TOUCH_INTERVAL_MS) artworkDao.touch(hash, now)
            }
        }
    }

    fun isStored(hash: String): Boolean = synchronized(lock) {
        artworkDao.get(hash)?.let { store.exists(it.fileName) } == true
    }

    fun totalSize(): Long = synchronized(lock) { artworkDao.totalSize() }

    /**
     * Startup maintenance: removes temporary leftovers, files without a database row, rows
     * without a file, and covers no track references any more.
     */
    fun cleanup(): CleanupReport = synchronized(lock) {
        val temporary = store.deleteTemporaryFiles()
        val rows = artworkDao.getAll()
        val known = rows.mapTo(HashSet()) { it.fileName }
        var orphanFiles = 0
        for (name in store.listFileNames()) {
            if (name !in known && store.delete(name)) orphanFiles++
        }
        val missing = rows.filter { !store.exists(it.fileName) }.map { it.hash }
        missing.chunked(CHUNK).forEach { artworkDao.delete(it) }
        val unreferenced = removeUnreferencedLocked()
        enforceBudgetLocked()
        CleanupReport(temporary, orphanFiles, missing.size, unreferenced)
    }

    /** Deletes covers that no track references (e.g. after removing a folder). */
    fun removeUnreferenced(): Int = synchronized(lock) { removeUnreferencedLocked() }

    private fun removeUnreferencedLocked(): Int {
        val referenced = trackDao.referencedArtworkHashes().toHashSet()
        val candidates = artworkDao.getAll().filter { it.hash !in referenced && it.hash !in inUse }
        return deleteLocked(candidates)
    }

    private fun enforceBudgetLocked() {
        val rows = artworkDao.getAll()
        val busy = rows.filter { store.isBusy(it.fileName) }.mapTo(HashSet()) { it.hash }
        val victims = planEviction(
            rows.map { CachedArtwork(it.hash, it.sizeBytes, it.lastAccessedAt) },
            budgetBytes,
            protected = inUse + busy,
        ).toHashSet()
        if (victims.isNotEmpty()) {
            val removed = deleteLocked(rows.filter { it.hash in victims })
            Log.i(TAG, "Evicted $removed covers to stay within the cache budget")
        }
    }

    /** Row first, then file: a crash in between leaves an orphan file that cleanup removes. */
    private fun deleteLocked(rows: List<ArtworkEntity>): Int {
        var removed = 0
        for (row in rows) {
            if (store.isBusy(row.fileName)) continue
            artworkDao.delete(listOf(row.hash))
            if (store.delete(row.fileName)) removed++
        }
        return removed
    }

    data class CleanupReport(val temporaryFiles: Int, val orphanFiles: Int, val missingFiles: Int, val unreferenced: Int)

    companion object {
        private const val TAG = "ArtworkCache"
        const val DEFAULT_BUDGET_BYTES = 100L * 1024 * 1024
        /** Minimum age of a cover's access time before playing it records a new one. */
        const val TOUCH_INTERVAL_MS = 60L * 60 * 1000
        private const val CHUNK = 500
    }
}
