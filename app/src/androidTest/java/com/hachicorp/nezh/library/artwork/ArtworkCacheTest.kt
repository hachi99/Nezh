package com.hachicorp.nezh.library.artwork

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.db.NezhDatabase
import com.hachicorp.nezh.library.db.TrackEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Cover cache rules, with real bitmap decoding/encoding on the device. */
@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, NezhDatabase::class.java).allowMainThreadQueries().build()
    private val directory = File(context.filesDir, "artwork-test-${UUID.randomUUID()}")
    private val store = ArtworkStore(directory)
    private var now = 1_000L

    private fun manager(budget: Long = ArtworkCacheManager.DEFAULT_BUDGET_BYTES) =
        ArtworkCacheManager(store, db.artworkDao(), db.trackDao(), budgetBytes = budget, clock = { now++ })

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    /** A noisy (hard to compress) image, like a real cover photo. */
    private fun cover(width: Int, height: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun referenceFromTrack(hash: String) {
        db.trackDao().upsertTracks(listOf(TrackEntity("p::$hash", "p", hash, "x.mp3", null, null, null, artworkHash = hash)))
    }

    @Test
    fun budgetIsOneHundredMegabytes() {
        assertEquals(100L * 1024 * 1024, ArtworkCacheManager.DEFAULT_BUDGET_BYTES)
    }

    @Test
    fun duplicateCoversAreStoredOnce() {
        val cache = manager()
        val image = cover(400, 400, seed = 1)
        val first = cache.put(image)!!
        val second = cache.put(image.copyOf())!!
        assertEquals(first, second)
        assertEquals(1, db.artworkDao().getAll().size)
        assertEquals(1, store.listFileNames().size)
    }

    @Test
    fun largeCoverIsDownscaledProportionallyAndRealSizeIsCounted() {
        val cache = manager()
        val hash = cache.put(cover(3000, 1500, seed = 2))!!
        val row = db.artworkDao().get(hash)!!
        assertEquals(512, row.width)
        assertEquals(256, row.height)
        assertEquals(store.sizeOf(row.fileName), row.sizeBytes)
        assertTrue(row.fileName.endsWith(".jpg")) // opaque image -> JPEG
    }

    @Test
    fun budgetIsEnforcedLeastRecentlyUsedFirst() {
        val probe = manager().also { it.put(cover(600, 600, seed = 10)) }
        val oneCover = probe.totalSize()
        db.artworkDao().delete(db.artworkDao().getAll().map { it.hash })
        store.listFileNames().forEach { store.delete(it) }

        val cache = manager(budget = oneCover * 5 / 2) // room for two covers
        val hashes = (20..23).map { seed -> cache.put(cover(600, 600, seed))!!.also(::referenceFromTrack) }
        val remaining = db.artworkDao().getAll().map { it.hash }.toSet()
        assertEquals(setOf(hashes[2], hashes[3]), remaining) // the two most recent
        assertTrue(cache.totalSize() <= oneCover * 5 / 2)
        assertEquals(2, store.listFileNames().size)
        // Evicted covers stay referenced by their tracks: they can be regenerated.
        assertEquals(hashes[0], db.trackDao().getTrack("p::${hashes[0]}")!!.artworkHash)
    }

    @Test
    fun coverInUseIsNeverEvicted() {
        val probe = manager().also { it.put(cover(600, 600, seed = 30)) }
        val oneCover = probe.totalSize()
        db.artworkDao().delete(db.artworkDao().getAll().map { it.hash })
        store.listFileNames().forEach { store.delete(it) }

        val cache = manager(budget = oneCover * 3 / 2) // room for one cover
        val playing = cache.put(cover(600, 600, seed = 31))!!
        cache.setInUse(playing)
        cache.put(cover(600, 600, seed = 32))
        cache.put(cover(600, 600, seed = 33))
        assertTrue(cache.isStored(playing))
    }

    @Test
    fun coverLargerThanTheWholeBudgetIsNotStored() {
        val cache = manager(budget = 1_000)
        val hash = cache.put(cover(600, 600, seed = 40))
        assertNotNull("the track still records that it has a cover", hash)
        assertNull(db.artworkDao().get(hash!!))
        assertTrue(store.listFileNames().isEmpty())
    }

    @Test
    fun evictedCoverCanBeRegenerated() {
        val cache = manager(budget = 1_000_000)
        val image = cover(600, 600, seed = 50)
        val hash = cache.put(image)!!
        referenceFromTrack(hash)
        db.artworkDao().delete(listOf(hash)) // as if evicted
        store.listFileNames().forEach { store.delete(it) }
        assertFalse(cache.isStored(hash))
        assertEquals(hash, cache.put(image))
        assertTrue(cache.isStored(hash))
    }

    @Test
    fun cleanupRemovesOrphansLeftoversMissingFilesAndUnreferencedCovers() {
        val cache = manager()
        val kept = cache.put(cover(300, 300, seed = 60))!!.also(::referenceFromTrack)
        val unreferenced = cache.put(cover(300, 300, seed = 61))!!
        val missing = cache.put(cover(300, 300, seed = 62))!!.also(::referenceFromTrack)
        store.delete(db.artworkDao().get(missing)!!.fileName) // row without file
        File(directory, "ffffffffffffffffffffffffffffffff.jpg").writeBytes(byteArrayOf(1)) // file without row
        File(directory, "tmp/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.jpg.tmp").writeBytes(byteArrayOf(1)) // interrupted write

        val report = cache.cleanup()

        assertEquals(1, report.temporaryFiles)
        assertEquals(1, report.orphanFiles)
        assertEquals(1, report.missingFiles)
        assertEquals(1, report.unreferenced)
        assertEquals(setOf(kept), db.artworkDao().getAll().map { it.hash }.toSet())
        assertEquals(listOf(db.artworkDao().get(kept)!!.fileName), store.listFileNames())
        assertFalse(unreferenced == kept)
    }

    @Test
    fun coverBeingReadIsNotDeleted() {
        val cache = manager()
        val hash = cache.put(cover(300, 300, seed = 70))!! // not referenced by any track
        val fileName = db.artworkDao().get(hash)!!.fileName
        store.read(fileName) {
            cache.removeUnreferenced()
            assertTrue("still on disk while being read", it.exists())
        }
    }
}
