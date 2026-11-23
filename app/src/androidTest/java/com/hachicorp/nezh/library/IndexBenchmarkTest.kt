package com.hachicorp.nezh.library

import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.library.db.FolderScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Persistent index benchmark against [BenchTreeProvider] (synthetic, cross-process, no storage).
 * Metadata reading is a fast fake here, to isolate scan + database costs; the real reader's cost
 * per file is measured by MetadataReaderTest.readCost.
 *
 * `adb logcat -s NezhPerfBench`
 */
@RunWith(AndroidJUnit4::class)
class IndexBenchmarkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = MetadataReader { AudioMetadata("Título", "Artista", "Álbum", 180_000, "audio/mpeg", null) }

    @Test
    fun indexBenchmark() {
        Log.i(TAG, "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
        Log.i(TAG, "| canciones | primer índice ms (recorrido+BD+metadatos) | reescaneo sin cambios ms | reapertura: biblioteca visible ms |")
        for (n in listOf(1_000, 5_000, 10_000)) {
            val key = FolderKey(BenchTreeProvider.AUTHORITY, "$n")
            val name = "bench-$n-${System.nanoTime()}.db"
            val first = TestLibrary(context, FakePermissions(), reader, name)
            val firstMs = timeScan(first) {
                first.library.load()
                first.library.addFolder(DocumentsContract.buildTreeDocumentUri(BenchTreeProvider.AUTHORITY, "$n"))
            }
            assertEquals(n, first.library.state.value.tracks.size)
            val rescanMs = timeScan(first) { first.library.refresh() }
            first.close(delete = false)

            val reopened = TestLibrary(context, FakePermissions(setOf(key)), reader, name)
            val start = SystemClock.elapsedRealtime()
            runBlocking(Dispatchers.Main) { reopened.library.load() }
            runBlocking { withTimeout(30_000) { reopened.library.state.first { it.loaded && it.tracks.size == n } } }
            val shownMs = SystemClock.elapsedRealtime() - start
            awaitIdle(reopened, 0)
            reopened.close(delete = true)

            Log.i(TAG, "| $n | $firstMs | $rescanMs | $shownMs |")
        }
    }

    private fun timeScan(stack: TestLibrary, action: () -> Unit): Long {
        SystemClock.sleep(5)
        val since = System.currentTimeMillis()
        val start = SystemClock.elapsedRealtime()
        runBlocking(Dispatchers.Main) { action() }
        awaitIdle(stack, since)
        return SystemClock.elapsedRealtime() - start
    }

    private fun awaitIdle(stack: TestLibrary, since: Long) = runBlocking {
        withTimeout(300_000) {
            while (true) {
                val folders = withContext(Dispatchers.IO) { stack.db.folderDao().getAll() }
                val state = stack.library.state.value
                if (folders.isNotEmpty() && folders.all { (it.lastCompletedScanAt ?: 0) >= since || it.lastScanStatus == FolderScanStatus.UNAVAILABLE } &&
                    state.loaded && !state.isScanning
                ) break
                SystemClock.sleep(20)
            }
        }
    }

    private companion object {
        const val TAG = "NezhPerfBench"
    }
}
