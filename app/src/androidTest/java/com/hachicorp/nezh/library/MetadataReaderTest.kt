package com.hachicorp.nezh.library

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The platform metadata reader on the format fixtures (tools/fixtures). */
@RunWith(AndroidJUnit4::class)
class MetadataReaderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reader = PlatformMetadataReader(instrumentation.targetContext)
    private val dir = File(instrumentation.targetContext.cacheDir, "metadata-fixtures").apply { mkdirs() }

    private fun fixture(name: String): Uri {
        val file = File(dir, name)
        instrumentation.context.assets.open("formats/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    @Test
    fun readsTagsDurationAndEmbeddedCover() {
        val m = reader.read(fixture("mp3_cbr_tagged.mp3"))!!
        assertEquals("Título de prueba", m.title)
        assertEquals("Artista de prueba", m.artist)
        assertEquals("Álbum de prueba", m.album)
        assertTrue("duration ${m.durationMs}", abs(m.durationMs!! - 20_010) < 500)
        assertNotNull(m.picture)
    }

    @Test
    fun readsFlacVorbisComments() {
        val m = reader.read(fixture("flac_tagged.flac"))!!
        assertEquals("Título FLAC", m.title)
        assertEquals("Artista FLAC", m.artist)
    }

    @Test
    fun fileWithoutTagsIsReadableWithNullTags() {
        val m = reader.read(fixture("mp3_cbr_untagged.mp3"))!!
        assertNull(m.title)
        assertNull(m.artist)
        assertNull(m.picture)
    }

    @Test
    fun corruptFileReturnsNullInsteadOfThrowing() {
        val m = reader.read(fixture("corrupt.mp3"))
        // Either unreadable (null) or readable without tags; it must never throw.
        if (m != null) assertNull(m.title)
    }

    @Test
    fun readCost() {
        val uri = fixture("mp3_cbr_tagged.mp3")
        reader.read(uri) // warm-up
        val runs = 30
        val start = SystemClock.elapsedRealtime()
        repeat(runs) { reader.read(uri) }
        Log.i("NezhPerfBench", "metadata read (tagged MP3 + cover, file URI): ${(SystemClock.elapsedRealtime() - start) / runs.toDouble()} ms/file")
    }
}
