package com.hachicorp.nezh.playback

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.TestTreeProvider
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.library.TrackIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * What "the current item changed" means, on the production player (buildNezhPlayer, real MP3s
 * served by the SAF-style provider):
 * - the current *track* (media id = library track id): what CurrentTrackReporter reports;
 * - the current *queue entry* (a position in the queue; a track can be queued twice): what error
 *   recovery needs, and what TitleFallback's metadata-only replacement must not change.
 */
@RunWith(AndroidJUnit4::class)
class CurrentItemIdentityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(TestTreeProvider.AUTHORITY, "id")
    private var player: ExoPlayer? = null

    /** One transition as seen by a listener: reason, media id, queue-entry uid, title at that time. */
    private data class Seen(val reason: Int, val mediaId: String?, val entry: Any?)

    private val transitions: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    private val reported: MutableList<String?> = Collections.synchronizedList(ArrayList())

    private fun track(path: String, fileTitle: String, tagTitle: String? = null): Track {
        val docId = "id/$path"
        return Track(
            id = TrackIdentity.of(TestTreeProvider.AUTHORITY, docId),
            documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
            title = tagTitle ?: fileTitle,
            relativePath = docId,
            folder = FolderKey(TestTreeProvider.AUTHORITY, "id"),
            fileTitle = fileTitle,
            tagTitle = tagTitle,
        )
    }

    // Untagged files have no title tag: TitleFallback gives them their file-name title.
    private val untaggedA = track("a.mp3", "a")
    private val tagged = track("b.mp3", "b", tagTitle = "Tagged title")
    // Two different files with the same name in different folders: same fallback title.
    private val sameName1 = track("d1/Track.mp3", "Track")
    private val sameName2 = track("d2/Track.mp3", "Track")

    @Before
    fun setUp() {
        fun size(asset: String) = instrumentation.context.assets.open(asset).use { it.readBytes().size }
        val untagged = size("formats/mp3_cbr_untagged.mp3")
        val spec = listOf(
            "id/a.mp3|$untagged|1|formats/mp3_cbr_untagged.mp3",
            "id/b.mp3|${size("formats/mp3_cbr_tagged.mp3")}|1|formats/mp3_cbr_tagged.mp3",
            "id/d1/Track.mp3|$untagged|1|formats/mp3_cbr_untagged.mp3",
            "id/d2/Track.mp3|$untagged|1|formats/mp3_cbr_untagged.mp3",
        ).joinToString("\n")
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, spec, null)
    }

    @After
    fun tearDown() {
        onMain { player?.release() }
        context.contentResolver.call(Uri.parse("content://${TestTreeProvider.AUTHORITY}"), TestTreeProvider.METHOD_SET, "", null)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!onMain(condition)) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out: $message")
            SystemClock.sleep(50)
        }
    }

    /** The production definition of the current queue entry. */
    private fun Player.entry(): Any? = currentQueueEntry()

    private fun Track.item(): MediaItem = toMediaItem().buildUpon().setUri(uri).build()

    /** The production player, with a recorder and the production reporter registered after it. */
    private fun start(vararg tracks: Track, extra: Player.Listener? = null): ExoPlayer = onMain {
        buildNezhPlayer(context).also { p ->
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions += Seen(reason, mediaItem?.mediaId, p.entry())
                }
            })
            p.addListener(CurrentTrackReporter { reported += it })
            extra?.let(p::addListener)
            p.setMediaItems(tracks.map { it.item() })
            p.prepare()
            p.play()
            player = p
        }
    }

    private fun title(p: Player) = onMain { p.mediaMetadata.title?.toString() }

    @Test
    fun aMetadataOnlyUpdateKeepsTheTrackAndTheQueueEntry() {
        val p = start(untaggedA)
        val entryBefore = onMain { p.entry() }
        waitUntil("file-name title applied") { p.mediaMetadata.title?.toString() == "a" }
        SystemClock.sleep(300)
        // Measured with Media3 1.11.1: TitleFallback's in-place replacement (same URI) is not a
        // media item transition at all; only the initial load is.
        assertEquals(listOf(Seen(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, untaggedA.id, entryBefore)), transitions.toList())
        assertEquals("same queue entry after the replacement", entryBefore, onMain { p.entry() })
        assertEquals("track reported once", listOf<String?>(untaggedA.id), reported.toList())
    }

    @Test
    fun aTaggedTrackGetsNoReplacement() {
        val p = start(tagged)
        waitUntil("playing") { p.isPlaying && p.currentPosition > 300 }
        assertEquals("Tagged title", title(p))
        assertEquals(1, transitions.size)
        assertEquals(listOf<String?>(tagged.id), reported.toList())
    }

    @Test
    fun aDifferentTrackIsANewTrackAndANewEntry() {
        val p = start(untaggedA, tagged)
        waitUntil("first playing") { p.isPlaying && p.currentPosition > 300 }
        onMain { p.seekToNextMediaItem() }
        waitUntil("second playing") { p.currentMediaItem?.mediaId == tagged.id && p.isPlaying }
        assertEquals(listOf<String?>(untaggedA.id, tagged.id), reported.toList())
        val entries = transitions.map { it.entry }.distinct()
        assertEquals(2, entries.size)
    }

    @Test
    fun twoFilesWithTheSameFallbackTitleAreDifferentTracks() {
        val p = start(sameName1, sameName2)
        waitUntil("first titled") { p.mediaMetadata.title?.toString() == "Track" && p.isPlaying }
        onMain { p.seekToNextMediaItem() }
        waitUntil("second titled") { p.currentMediaItem?.mediaId == sameName2.id && p.mediaMetadata.title?.toString() == "Track" }
        assertNotEquals(sameName1.id, sameName2.id)
        assertEquals("the title is never the identity", listOf<String?>(sameName1.id, sameName2.id), reported.toList())
    }

    @Test
    fun theSameTrackQueuedTwiceIsOneTrackButTwoEntries() {
        val p = start(tagged, tagged)
        waitUntil("first playing") { p.isPlaying && p.currentPosition > 300 }
        onMain { p.seekToNextMediaItem() }
        waitUntil("second entry playing") { p.currentMediaItemIndex == 1 && p.isPlaying }
        assertEquals("same track: reported once", listOf<String?>(tagged.id), reported.toList())
        val seen = transitions.toList()
        assertEquals(listOf(tagged.id, tagged.id), seen.map { it.mediaId })
        assertNotEquals("different queue entries", seen[0].entry, seen[1].entry)
    }

    @Test
    fun aNewQueueStartingOnTheCurrentTrackIsANewEntry() {
        val p = start(tagged)
        waitUntil("playing") { p.isPlaying && p.currentPosition > 300 }
        onMain { p.setMediaItems(listOf(tagged.item(), untaggedA.item()), 0, 0) }
        waitUntil("new queue loaded") { p.mediaItemCount == 2 }
        SystemClock.sleep(300)
        val seen = transitions.toList()
        assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, seen.last().reason)
        assertEquals(tagged.id, seen.last().mediaId)
        assertNotEquals("new queue: new entry", seen.first().entry, seen.last().entry)
        assertEquals("same track: not reported again", listOf<String?>(tagged.id), reported.toList())
    }

    @Test
    fun skippingInsideTheTracksEventDoesNotMoveTheFallbackTitleToTheNextTrack() {
        // A listener registered after TitleFallback skips the untagged file as soon as its tracks
        // are known (as error recovery does for a file that cannot sound).
        val skipOnce = object : Player.Listener {
            var done = false
            override fun onTracksChanged(tracks: Tracks) {
                val p = player ?: return
                if (!done && !tracks.isEmpty && p.currentMediaItemIndex == 0) {
                    done = true
                    p.seekToNextMediaItem()
                }
            }
        }
        val p = start(untaggedA, tagged, extra = skipOnce)
        waitUntil("second playing") { p.currentMediaItem?.mediaId == tagged.id && p.isPlaying && p.currentPosition > 300 }
        assertEquals("the tagged track keeps its own title", "Tagged title", title(p))
        assertEquals(tagged.id, onMain { p.getMediaItemAt(1).mediaId })
        assertEquals(untaggedA.id, onMain { p.getMediaItemAt(0).mediaId })
    }
}
