package com.hachicorp.nezh.library.artwork

import androidx.core.graphics.scale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A cover ready to be stored. */
class EncodedArtwork(val bytes: ByteArray, val width: Int, val height: Int, val extension: String)

/**
 * Turns an embedded cover into a small file: decoded with subsampling (never at full size), scaled
 * proportionally to fit [maxDimension] (never enlarged), then encoded as JPEG when opaque or PNG
 * when it actually has transparent pixels.
 */
class ArtworkEncoder(private val maxDimension: Int = DEFAULT_MAX_DIMENSION) {

    /** Returns null if [original] is not a decodable image. Blocking, CPU-bound. */
    fun encode(original: ByteArray): EncodedArtwork? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scale = minOf(1f, maxDimension.toFloat() / max(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            decoded.scale(width, height).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        try {
            // hasAlpha() only says the format *can* hold transparency (opaque PNG covers report it
            // too); check the pixels so opaque images get the much smaller JPEG.
            val opaque = !bitmap.hasAlpha() || !hasTransparentPixels(bitmap)
            val out = ByteArrayOutputStream()
            val ok = if (opaque) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!ok) return null
            return EncodedArtwork(out.toByteArray(), bitmap.width, bitmap.height, if (opaque) "jpg" else "png")
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 512
        private const val JPEG_QUALITY = 85
    }
}
