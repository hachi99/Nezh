package com.hachicorp.nezh.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.FolderKey
import com.hachicorp.nezh.library.FolderStatus
import com.hachicorp.nezh.library.LibraryFolder
import com.hachicorp.nezh.library.LibraryState
import com.hachicorp.nezh.library.TitleOrder
import com.hachicorp.nezh.library.Track
import com.hachicorp.nezh.ui.theme.NezhTheme
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Main-thread cost per frame of the real library list (LibraryTab) with 3,000 tracks while it
 * scrolls at a constant fling-like speed, from the platform's FrameMetrics (input + animation and
 * recomposition + measure/layout + draw recording). Covers are real 512 px JPEG files, like the
 * artwork cache stores; the same list is also measured with no covers to isolate their cost.
 *
 * Logs (NezhPerf "listBench"). A measurement aid, not a pass/fail test. Uses no Espresso (the
 * project's Espresso version does not run on API 37).
 */
@RunWith(AndroidJUnit4::class)
class LibraryListBenchmarkTest {

    private val folder = FolderKey("authority", "music")

    private fun library(withCovers: Boolean): LibraryState {
        val random = Random(1)
        val words = listOf("amor", "Éxito", "noche", "¿Dónde", "1999", "Zafiro", "luz", "árbol", "río", "Canción", "mar", "Ojos")
        val tracks = List(TRACKS) { i ->
            Track(
                id = "t$i",
                documentUri = "content://x/$i",
                title = List(2) { words[random.nextInt(words.size)] }.joinToString(" ") + " $i",
                relativePath = "Música/Artista/Álbum/$i.mp3",
                folder = folder,
                artist = "Artista ${i % 150}",
                album = "Álbum ${i / 3}",
                artworkUri = if (withCovers) Uri.fromFile(covers[i / 3 % covers.size]).toString() else null,
            )
        }
        val (sorted, sections) = TitleOrder.sort(tracks)
        return LibraryState(
            loaded = true,
            folders = listOf(LibraryFolder(folder, Uri.EMPTY, "Música", FolderStatus.READY, sorted.size)),
            tracks = sorted,
            sections = sections,
        )
    }

    /** Scrolls [FRAMES] frames at [PX_PER_FRAME] and returns UI-thread ms per frame, sorted. */
    private fun measure(label: String, library: LibraryState) {
        val frames = Collections.synchronizedList(ArrayList<DoubleArray>())
        val done = CountDownLatch(1)
        val metricsThread = HandlerThread("metrics").apply { start() }
        var recording = false
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (!recording) return@OnFrameMetricsAvailableListener
            fun ms(id: Int) = metrics.getMetric(id) / 1e6
            frames += doubleArrayOf(
                ms(FrameMetrics.INPUT_HANDLING_DURATION) + ms(FrameMetrics.ANIMATION_DURATION) +
                    ms(FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(FrameMetrics.DRAW_DURATION),
                ms(FrameMetrics.ANIMATION_DURATION),
                ms(FrameMetrics.LAYOUT_MEASURE_DURATION),
            )
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
                val state = LazyListState()
                activity.setContent {
                    NezhTheme {
                        LibraryTab(library, currentMediaId = null, onTrackClick = {}, onAddToQueue = {}, onAddFolder = {}, listState = state)
                    }
                    LaunchedEffect(Unit) {
                        repeat(30) { withFrameNanos { } } // settle
                        recording = true
                        repeat(FRAMES) {
                            state.scrollBy(PX_PER_FRAME)
                            withFrameNanos { }
                        }
                        repeat(5) { withFrameNanos { } }
                        recording = false
                        done.countDown()
                    }
                }
            }
            check(done.await(120, TimeUnit.SECONDS)) { "scroll did not finish" }
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        }
        metricsThread.quitSafely()
        val ui = frames.map { it[0] }.sorted()
        val anim = frames.map { it[1] }.sorted()
        val layout = frames.map { it[2] }.sorted()
        fun p(list: List<Double>, q: Double) = "%.1f".format(list[((list.size - 1) * q).toInt()])
        Log.i(
            "NezhPerf",
            "listBench $label frames=${ui.size} uiMs p50=${p(ui, .5)} p90=${p(ui, .9)} p99=${p(ui, .99)} max=${p(ui, 1.0)} " +
                ">16ms=${ui.count { it > 16 }} >32ms=${ui.count { it > 32 }} " +
                "recompose p50=${p(anim, .5)} p90=${p(anim, .9)} layout p50=${p(layout, .5)} p90=${p(layout, .9)}",
        )
    }

    @Test
    fun scrollThreeThousandTracks() {
        measure("warmup", library(withCovers = false)) // JIT warm-up; leaves the cover cache cold for r0
        repeat(2) { round ->
            measure("covers r$round", library(withCovers = true))
            measure("noCovers r$round", library(withCovers = false))
        }
    }

    companion object {
        private const val TRACKS = 3000
        /** ~2.6 rows per frame at 60 Hz on a 420 dpi screen: a brisk fling (~10,000 px/s). */
        private const val PX_PER_FRAME = 170f
        private const val FRAMES = 600
        private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bench-covers")

        /** 300 distinct 512 px covers (noisy, so the JPEGs have a realistic size). */
        private val covers: List<File> by lazy {
            dir.mkdirs()
            val random = Random(2)
            List(300) { n ->
                File(dir, "cover$n.jpg").also { file ->
                    if (!file.isFile) {
                        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                        val block = IntArray(16)
                        for (y in 0 until 512 step 4) for (x in 0 until 512 step 4) {
                            if (random.nextInt(7) == 0) {
                                block.fill(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                                bitmap.setPixels(block, 0, 4, x, y, 4, 4)
                            }
                        }
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bitmap.recycle()
                    }
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            dir.deleteRecursively()
        }
    }
}
