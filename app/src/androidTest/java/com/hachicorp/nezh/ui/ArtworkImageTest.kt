package com.hachicorp.nezh.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hachicorp.nezh.ui.theme.NezhTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cover shown by [ArtworkImage] always belongs to the current artwork URI: switching to
 * another cover never shows the previous one, switching to "no cover" shows the placeholder, a
 * missing (evicted) file shows the placeholder, and a regenerated cover appears when the library
 * reports it again. Checked on the real rendered pixels.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkImageTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dir = File(instrumentation.targetContext.cacheDir, "artwork-image-test")
    private val uri = mutableStateOf<String?>(null)
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var center = IntArray(2)

    private fun cover(name: String, color: Int): String {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file).toString()
    }

    @Before
    fun setUp() {
        dir.mkdirs()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                NezhTheme {
                    Box(Modifier.onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        center = intArrayOf(bounds.center.x.toInt(), bounds.center.y.toInt())
                    }) {
                        ArtworkImage(artworkUri = uri.value, size = 48.dp)
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        scenario.close()
        dir.deleteRecursively()
    }

    private fun setUri(value: String?) = instrumentation.runOnMainSync { uri.value = value }

    /** The pixel at the image's center, as actually displayed on screen. */
    private fun pixel(): Int {
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val screen = instrumentation.uiAutomation.takeScreenshot() ?: return 0
        val color = screen.getPixel(origin[0] + center[0], origin[1] + center[1])
        screen.recycle()
        return color
    }

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 70 && Color.blue(c) < 70
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 70 && Color.green(c) < 70
    private fun isCover(c: Int) = isRed(c) || isBlue(c)

    private fun waitFor(message: String, condition: (Int) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition(pixel())) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out: $message")
            SystemClock.sleep(20)
        }
    }

    /**
     * After a change: the previous image disappears within a frame or two (the screen cannot
     * change sooner), and then it never comes back while sampling for a while.
     */
    private fun neverShows(message: String, forbidden: (Int) -> Boolean) {
        waitFor("$message (it never went away)") { !forbidden(it) }
        val end = SystemClock.elapsedRealtime() + 400
        while (SystemClock.elapsedRealtime() < end) {
            assertFalse(message, forbidden(pixel()))
            SystemClock.sleep(10)
        }
    }

    @Test
    fun switchingCoversNeverShowsThePreviousOne() {
        val red = cover("red.jpg", Color.RED)
        val blue = cover("blue.jpg", Color.BLUE)
        setUri(red)
        waitFor("first cover shown") { isRed(it) }

        setUri(blue)
        neverShows("previous cover shown after switching") { isRed(it) }
        waitFor("new cover shown") { isBlue(it) }

        // Back to a cover already in memory: shown at once, still never the other one.
        setUri(red)
        neverShows("previous cover shown after switching back") { isBlue(it) }
        waitFor("cover shown again") { isRed(it) }
    }

    @Test
    fun trackWithoutCoverShowsThePlaceholder() {
        setUri(cover("red.jpg", Color.RED))
        waitFor("cover shown") { isRed(it) }
        setUri(null)
        neverShows("a cover remained for a track without one") { isCover(it) }
    }

    @Test
    fun missingFileShowsThePlaceholderAndARegeneratedCoverAppears() {
        val file = File(dir, "evicted.jpg")
        val evicted = Uri.fromFile(file).toString()
        setUri(evicted) // the file is gone (evicted from the cache)
        neverShows("something drawn for a missing file") { isCover(it) }

        // The library reports no cover while it is missing, then the regenerated one.
        setUri(null)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100) // at least one frame without a cover, as in the app
        cover("evicted.jpg", Color.BLUE)
        setUri(evicted)
        waitFor("regenerated cover shown") { isBlue(it) }
    }
}
