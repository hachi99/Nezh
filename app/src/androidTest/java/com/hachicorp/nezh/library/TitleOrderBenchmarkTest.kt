package com.hachicorp.nezh.library

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Cost of the library's title order on a device: the library is re-sorted (off the main thread)
 * every time it changes, e.g. on each progressive update while a folder is scanned.
 */
@RunWith(AndroidJUnit4::class)
class TitleOrderBenchmarkTest {

    @Test
    fun sortTenThousandTracks() {
        val random = Random(42)
        val words = listOf("amor", "Éxito", "noche", "¿Dónde", "1999", "Zafiro", "luz", "ÁRBOL", "río", "Canción", "東京", "mar")
        val folder = FolderKey("authority", "music")
        val tracks = List(10_000) { i ->
            val title = List(3) { words[random.nextInt(words.size)] }.joinToString(" ")
            Track(id = "t$i", documentUri = "content://x/$i", title = title, relativePath = "Música/$i.mp3", folder = folder)
        }
        TitleOrder.sort(tracks) // warm-up (class loading, JIT)
        val times = List(5) {
            val start = SystemClock.elapsedRealtime()
            val (sorted, sections) = TitleOrder.sort(tracks.shuffled(random))
            assertEquals(tracks.size, sorted.size)
            assertEquals(tracks.size, sections.sumOf { it.size })
            SystemClock.elapsedRealtime() - start
        }
        Log.i("NezhPerf", "titleOrder tracks=${tracks.size} ms=$times median=${times.sorted()[2]}")
        // Breakdown, for reference: collation keys alone, and the section of every title.
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        var start = SystemClock.elapsedRealtime()
        tracks.forEach { collator.getCollationKey(it.title) }
        val keysMs = SystemClock.elapsedRealtime() - start
        start = SystemClock.elapsedRealtime()
        tracks.forEach { TitleOrder.sectionOf(it.title) }
        Log.i("NezhPerf", "titleOrder keysMs=$keysMs sectionsMs=${SystemClock.elapsedRealtime() - start}")
    }
}
