package com.hachicorp.nezh.library

import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.playback.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library scan benchmark against [BenchTreeProvider] (synthetic, cross-process, no storage use).
 *
 * Run: `gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=
 * com.hachicorp.nezh.library.LibraryScanBenchmarkTest`, then `adb logcat -s NezhPerfBench`.
 *
 * Reports medians of [RUNS] runs after one warm-up. Main-thread rows time the work the app
 * currently does on the main thread with a library of that size.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScanBenchmarkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scanner = SafTreeScanner(context.contentResolver)

    /** Real ExternalStorageProvider, emulator only: needs a folder named NezhBench authorized in Nezh
     *  (tools/library-bench/generate_library.sh + "Añadir carpeta"). Skipped otherwise. */
    @Test
    fun realProviderParallelism() {
        val tree = context.contentResolver.persistedUriPermissions
            .map { it.uri }
            .firstOrNull { uri -> FolderKey.of(uri)?.treeDocumentId?.endsWith("/NezhBench") == true }
        assumeTrue("No authorized NezhBench folder on this device", tree != null)
        val key = FolderKey.of(tree!!)!!
        Log.i(TAG, "Proveedor real (${key.authority}), API ${Build.VERSION.SDK_INT}")
        Log.i(TAG, "| paralelismo | canciones | consultas SAF | recorrido ms (mediana de 2) |")
        scanner.scan(tree, key, "Bench", checkCancelled = {}, parallelism = 4) // warm-up
        for (p in listOf(1, 2, 4, 8, 16)) {
            val runs = List(2) { scanner.scan(tree, key, "Bench", checkCancelled = {}, parallelism = p) as ScanOutcome.Success }
            val walk = runs.map { it.stats.walkMs }.sorted()
            Log.i(TAG, "| $p | ${runs[0].tracks.size} | ${runs[0].stats.queries} | ${walk[0]}..${walk[1]} |")
        }
    }

    /** Synthetic provider: effect of parallelism with 10,000 tracks. */
    @Test
    fun syntheticParallelism() {
        Log.i(TAG, "| paralelismo (sintético) | canciones | recorrido ms (mediana de 3) |")
        scanOnce(10_000, 4) // warm-up
        for (p in listOf(1, 2, 4, 8)) {
            val walk = List(3) { scanOnce(10_000, p).stats.walkMs }.sorted()[1]
            Log.i(TAG, "| $p | 10000 | $walk |")
        }
    }

    @Test
    fun scanBenchmark() {
        Log.i(TAG, "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
        Log.i(TAG, "| canciones | consultas SAF | filas | escaneo ms | recorrido ms | lista+orden ms | heap retenido MB | bytes/canción | main: fusión ms | main: filtro cola ms | main: MediaItems ms | main: igualdad estado ms |")
        for (n in SIZES) {
            scanOnce(n) // warm-up (process start, JIT)
            val runs = List(RUNS) { measure(n) }
            val m = runs.sortedBy { it.scanMs }[RUNS / 2]
            Log.i(TAG, "| $n | ${m.queries} | ${m.entries} | ${m.scanMs} | ${m.walkMs} | ${m.buildMs} | ${"%.1f".format(m.heapMb)} | ${m.bytesPerTrack} | ${m.mergeMs} | ${m.filterMs} | ${m.mediaItemsMs} | ${m.equalityMs} |")
        }
    }

    private data class Result(
        val queries: Int, val entries: Int, val scanMs: Long, val walkMs: Long, val buildMs: Long,
        val heapMb: Double, val bytesPerTrack: Long,
        val mergeMs: Long, val filterMs: Long, val mediaItemsMs: Long, val equalityMs: Long,
    )

    private fun scanOnce(n: Int, parallelism: Int = SafTreeScanner.DEFAULT_PARALLELISM): ScanOutcome.Success {
        val treeUri = DocumentsContract.buildTreeDocumentUri(BenchTreeProvider.AUTHORITY, "$n")
        val key = FolderKey(BenchTreeProvider.AUTHORITY, "$n")
        val outcome = scanner.scan(treeUri, key, "Bench", checkCancelled = {}, parallelism = parallelism)
        check(outcome is ScanOutcome.Success) { "scan failed: $outcome" }
        assertEquals(n, outcome.tracks.size)
        return outcome
    }

    private fun measure(n: Int): Result {
        val before = usedHeap()
        val start = SystemClock.elapsedRealtime()
        val outcome = scanOnce(n)
        val scanMs = SystemClock.elapsedRealtime() - start
        val retained = usedHeap() - before
        val tracks = outcome.tracks
        // A rescan produces new Track instances with the same content, as in the app.
        val rescanned = scanOnce(n).tracks

        // Work currently done on the main thread, timed on the main thread.
        var mergeMs = 0L
        var filterMs = 0L
        var mediaItemsMs = 0L
        var equalityMs = 0L
        instrumentation.runOnMainSync {
            mergeMs = time { mergeUnique(listOf(tracks)) { it.id } }
            val state = LibraryState(loaded = true, tracks = tracks)
            filterMs = time { state.queueFilter() }
            mediaItemsMs = time { tracks.map { it.toMediaItem() } }
            // StateFlow compares the new state with the old one (equals) on every update.
            val sameContent = state.copy(tracks = rescanned)
            equalityMs = time { check(state == sameContent) }
        }
        return Result(
            outcome.stats.queries, outcome.stats.entries, scanMs, outcome.stats.walkMs, outcome.stats.buildMs,
            retained / 1_048_576.0, retained / n, mergeMs, filterMs, mediaItemsMs, equalityMs,
        )
    }

    private inline fun time(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { runtime.gc(); System.runFinalization() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val TAG = "NezhPerfBench"
        val SIZES = listOf(100, 1_000, 5_000, 10_000)
        const val RUNS = 3
    }
}
