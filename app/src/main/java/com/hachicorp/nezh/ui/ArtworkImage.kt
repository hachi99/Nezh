package com.hachicorp.nezh.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A track's cached cover (a local file:// URI from the persistent artwork cache), decoded off the
 * main thread at about the size shown and kept in a small in-memory cache of thumbnails.
 *
 * - The image state belongs to one cover: when [artworkUri] changes (another track in a reused
 *   row, or a track change in "Sonando") it is reset at once, so a previous track's cover is never
 *   shown, and a track without a cover shows the placeholder.
 * - A cover already in memory is drawn in the same frame, without starting any work.
 * - One drawing node, no clipping layer: cheap enough for fast scrolling through long lists.
 * - Shows a plain placeholder while loading, when the track has no cover, or if the file is gone
 *   (evicted); the library then reports the cover again once it is regenerated.
 */
@Composable
internal fun ArtworkImage(artworkUri: String?, size: Dp, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val key = artworkUri?.let { "$it@$sizePx" }
    val image = remember(key) { mutableStateOf(key?.let(Thumbnails::get)) }
    if (artworkUri != null && key != null && image.value == null) {
        LaunchedEffect(key) {
            image.value = withContext(Thumbnails.decoding) { Thumbnails.decode(artworkUri, sizePx) }
                ?.also { Thumbnails.put(key, it) }
        }
    }
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Spacer(
        modifier.size(size).drawBehind {
            // Read here, so a cover that finishes loading only redraws this node.
            val bitmap = image.value
            if (bitmap == null) {
                drawRect(placeholder)
            } else {
                // Center crop to the square.
                val side = minOf(bitmap.width, bitmap.height)
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset((bitmap.width - side) / 2, (bitmap.height - side) / 2),
                    srcSize = IntSize(side, side),
                    dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                )
            }
        },
    )
}

/** Decoded thumbnails in memory, bounded to 16 MB (about 370 list covers of 147 px, RGB 565). */
private object Thumbnails {
    /** Few parallel decodes: fast scrolling must not flood the IO pool. */
    val decoding = Dispatchers.IO.limitedParallelism(2)

    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) =
            value.width * value.height * if (value.config == ImageBitmapConfig.Rgb565) 2 else 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) { cache.put(key, bitmap) }

    /** Null if the file no longer exists or cannot be decoded. */
    fun decode(artworkUri: String, targetPx: Int): ImageBitmap? {
        val path = artworkUri.toUri().path ?: return null
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Cached covers are JPEG unless they need transparency: 2 bytes per pixel is enough.
            if (bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        // Power-of-two sampling can leave it up to 2x larger than shown: scale to the exact size so
        // the memory cache holds as many covers as possible.
        val side = minOf(decoded.width, decoded.height)
        if (side <= targetPx * 5 / 4) return decoded.asImageBitmap()
        val scale = targetPx.toFloat() / side
        val scaled = decoded.scale(
            (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1),
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled.asImageBitmap()
    }
}
