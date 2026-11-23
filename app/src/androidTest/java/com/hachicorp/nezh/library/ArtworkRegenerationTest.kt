package com.hachicorp.nezh.library

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hachicorp.nezh.library.artwork.ArtworkCacheManager
import com.hachicorp.nezh.library.db.ArtworkEntity
import com.hachicorp.nezh.library.db.MetadataState
import com.hachicorp.nezh.playback.CachedArtworkBitmapLoader
import com.hachicorp.nezh.playback.CurrentTrackReporter
import com.hachicorp.nezh.playback.buildNezhPlayer
import com.hachicorp.nezh.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * End to end: a track's cached cover is evicted by the real 100 MB LRU budget, the track stays in
 * the library, it is played with the production player and the production current-track
 * listener, and the cover is regenerated from the audio file (read through the SAF-style provider
 * with the real metadata reader) without interrupting playback.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ArtworkRegenerationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stack = TestLibrary(
        context,
        FakePermissions(),
        metadata = PlatformMetadataReader(context), // real reading, through the provider
        artworkBudget = ArtworkCacheManager.DEFAULT_BUDGET_BYTES, // the real 100 MB limit
    )
    private var player: ExoPlayer? = null

    private val trackA = TrackIdentity.of(TestTreeProvider.AUTHORITY, "art/a.mp3")

    @After
    fun tearDown() {
        onMain { player?.release() }
        stack.close()
        setTree("")
    }

    private fun setTree(spec: String) {
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out: $message")
            SystemClock.sleep(50)
        }
    }

    private fun libraryTrack(id: String) = stack.library.state.value.tracks.firstOrNull { it.id == id }

    /** Records which bitmap requests reach the real loader. */
    private class RecordingLoader : BitmapLoader {
        val loadedUris: MutableList<Uri> = Collections.synchronizedList(ArrayList())
        val fromMetadata = AtomicInteger()
        override fun supportsMimeType(mimeType: String) = true
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = Futures.immediateFailedFuture(UnsupportedOperationException())
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            loadedUris += uri
            return Futures.immediateFailedFuture(UnsupportedOperationException())
        }
        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            fromMetadata.incrementAndGet()
            return null
        }
    }

    @Test
    fun evictedCoverIsRegeneratedWhenTheTrackIsPlayed() {
        // 1. Two real tracks with embedded covers, indexed with the real metadata reader.
        setTree(
            "art/a.mp3|321481|1|formats/mp3_cbr_tagged.mp3\n" +
                "art/b.mp3|111073|1|formats/mp3_large_artwork.mp3"
        )
        val since = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) {
            stack.library.load()
            stack.library.addFolder(DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "art"))
        }
        waitUntil("indexing") {
            val tracks = stack.db.trackDao().getTracks(listOf(trackA))
            val state = stack.library.state.value
            tracks.singleOrNull()?.metadataState == MetadataState.READ && !state.isScanning &&
                (stack.db.folderDao().getAll().singleOrNull()?.lastCompletedScanAt ?: 0) >= since &&
                libraryTrack(trackA)?.artworkUri != null
        }
        val hashA = stack.db.trackDao().getTrack(trackA)!!.artworkHash!!
        val before = libraryTrack(trackA)!!
        assertTrue(stack.artwork.isStored(hashA))

        // 2. Evict A through the real 100 MB LRU: make it the least recently used cover, fill the
        //    cache with other (newer) covers, then store one more cover.
        stack.db.artworkDao().touch(hashA, 1)
        val fillerSize = 10L * 1024 * 1024
        repeat(10) { i ->
            val hash = "f".repeat(55) + "%09d".format(i)
            val name = "$hash.jpg"
            stack.store.write(name, ByteArray(fillerSize.toInt()))
            stack.db.artworkDao().upsert(ArtworkEntity(hash, name, fillerSize, 512, 512, System.currentTimeMillis()))
        }
        val extraCover = File(context.cacheDir, "extra-cover.png").let { file ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.readBytes()
        }
        stack.artwork.put(extraCover)

        // 3. Evicted: file gone, the track is intact in Room and in the library (no cover).
        assertFalse(stack.artwork.isStored(hashA))
        assertTrue(stack.artwork.totalSize() <= ArtworkCacheManager.DEFAULT_BUDGET_BYTES)
        assertEquals(hashA, stack.db.trackDao().getTrack(trackA)!!.artworkHash)
        waitUntil("library reflects the eviction") { libraryTrack(trackA)?.let { it.artworkUri == null } == true }
        assertEquals(before.title, libraryTrack(trackA)!!.title)

        // An item built before the eviction still points to the missing file: the session loader
        // must not try to read it and falls back to the embedded cover.
        val staleItem = before.toMediaItem()
        val recorder = RecordingLoader()
        CachedArtworkBitmapLoader(recorder).loadBitmapFromMetadata(staleItem.mediaMetadata)
        assertTrue("missing cover file was not read", recorder.loadedUris.isEmpty())
        assertEquals(1, recorder.fromMetadata.get())

        // 4. Play A with the production player and the service's current-track listener.
        val evictedTrack = libraryTrack(trackA)!!
        var decoderInits = 0
        player = onMain {
            buildNezhPlayer(context).apply {
                addListener(CurrentTrackReporter { id -> stack.library.onPlaybackTrackChanged(id) })
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        decoderInits++
                    }
                })
                setMediaItem(evictedTrack.toMediaItem().buildUpon().setUri(evictedTrack.uri).build())
                prepare()
                play()
            }
        }

        // 5–6. Regenerated from the audio file while playing; available again to the library.
        waitUntil("cover regenerated") { stack.artwork.isStored(hashA) }
        waitUntil("library shows the cover again") { libraryTrack(trackA)?.artworkUri != null }
        val regenerated = libraryTrack(trackA)!!
        assertTrue(File(regenerated.artworkUri!!.toUri().path!!).isFile)

        // Playback was not interrupted: playing, advancing, no error, decoder set up once.
        val position1 = onMain { player!!.currentPosition }
        SystemClock.sleep(700)
        onMain {
            val p = player!!
            assertNull(p.playerError)
            assertTrue(p.isPlaying)
            assertEquals(Player.STATE_READY, p.playbackState)
            assertTrue("position advances", p.currentPosition > position1)
        }
        assertEquals(1, decoderInits)

        // The regenerated cover is what the session loads now, from an existing file.
        val freshRecorder = RecordingLoader()
        CachedArtworkBitmapLoader(freshRecorder).loadBitmapFromMetadata(regenerated.toMediaItem().mediaMetadata)
        assertEquals(1, freshRecorder.loadedUris.size)
        assertNotNull(freshRecorder.loadedUris.single().path?.let { File(it) }?.takeIf { it.isFile })

        // The cover in use is protected: more covers over budget do not evict it, and the
        // cache stays within 100 MB.
        repeat(3) { i ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() + i * 4097) }
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            stack.db.artworkDao().touch(hashA, 1) // oldest again: only "in use" protects it
            stack.artwork.put(bytes)
        }
        assertTrue("cover in use kept", stack.artwork.isStored(hashA))
        assertTrue(stack.artwork.totalSize() <= ArtworkCacheManager.DEFAULT_BUDGET_BYTES)
    }

    private fun String.toUri(): Uri = Uri.parse(this)
}
