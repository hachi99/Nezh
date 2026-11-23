package com.hachicorp.nezh.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Session bitmap loader that prefers Nezh's cached cover (MediaMetadata.artworkUri, a small file)
 * over the picture embedded in the audio file (artworkData), which Media3's default loader decodes
 * first. A 3000×3000 embedded cover measured ~9 MB of native memory while playing; the cached
 * copy is at most 512×512. Falls back to the embedded picture when no cached cover exists.
 */
@OptIn(UnstableApi::class)
internal class CachedArtworkBitmapLoader(private val delegate: BitmapLoader) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = delegate.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val cached = metadata.artworkUri
        // An evicted cover's file no longer exists: never try to read it, use the embedded one.
        if (cached != null && cached.scheme == "file" && cached.path?.let { File(it).isFile } == true) {
            return delegate.loadBitmap(cached)
        }
        return delegate.loadBitmapFromMetadata(metadata)
    }
}
