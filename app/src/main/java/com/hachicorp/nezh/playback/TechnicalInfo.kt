package com.hachicorp.nezh.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * Technical information about the current track, in two groups that are never mixed:
 * - [stream]: what the player reports for the audio stream it is actually playing (codec,
 *   sample rate, channels, bit depth when it is known, bitrate labelled by where it comes from);
 * - [file]: what is known about the file (name extension, size, duration from the index).
 * Unknown values are left out, never guessed. Labels and values are ready to show (Spanish).
 */
data class TechnicalInfo(val stream: List<Pair<String, String>>, val file: List<Pair<String, String>>) {
    val isEmpty: Boolean get() = stream.isEmpty() && file.isEmpty()
}

@OptIn(UnstableApi::class)
internal fun technicalInfo(format: Format?, fileName: String?, sizeBytes: Long?, durationMs: Long?): TechnicalInfo {
    val stream = ArrayList<Pair<String, String>>()
    if (format != null) {
        codecName(format.sampleMimeType, format.codecs)?.let { stream += "Códec" to it }
        containerName(format.containerMimeType)?.let { stream += "Contenedor" to it }
        if (format.sampleRate != Format.NO_VALUE && format.sampleRate > 0) stream += "Frecuencia de muestreo" to sampleRate(format.sampleRate)
        bitDepth(format.pcmEncoding)?.let { stream += "Profundidad de bits" to it }
        if (format.channelCount != Format.NO_VALUE && format.channelCount > 0) stream += "Canales" to channels(format.channelCount)
        bitrate(format)?.let { stream += it }
        format.codecs?.takeIf { it.isNotBlank() }?.let { stream += "Códec (identificador)" to it }
    }
    val file = ArrayList<Pair<String, String>>()
    fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }?.let { file += "Formato del archivo" to it.uppercase(Locale.ROOT) }
    if (sizeBytes != null && sizeBytes > 0) file += "Tamaño" to fileSize(sizeBytes)
    if (durationMs != null && durationMs > 0) file += "Duración" to duration(durationMs)
    return TechnicalInfo(stream, file)
}

/**
 * The average bitrate, labelled by its source. Only [Format.averageBitrate] is used: the peak
 * bitrate is a declared maximum, not what the file uses. Sources in Media3 1.11.1:
 * - MP4/M4A: always computed from the sample table (bytes of audio ÷ duration); it replaces the
 *   value declared in the file, so it is exact but not "declared".
 * - MP3: computed from the Xing/Info/VBRI header (bytes ÷ duration); without such a header, the
 *   first frame's bitrate is assumed to be constant, which is only exact for CBR files.
 * - WAV: the constant PCM rate from the header (exact).
 * - Other containers (e.g. Ogg Vorbis nominal bitrate): the value declared by the encoder.
 * FLAC and Opus report none, so nothing is shown.
 */
@OptIn(UnstableApi::class)
private fun bitrate(format: Format): Pair<String, String>? {
    val bps = format.averageBitrate.takeIf { it != Format.NO_VALUE && it > 0 } ?: return null
    val kbps = "${(bps + 500) / 1000} kbps"
    return when (format.containerMimeType) {
        "audio/mp4", "video/mp4", "audio/wav", "audio/x-wav" -> "Bitrate promedio" to "$kbps (calculado)"
        // Exact with a Xing/Info/VBRI header or for CBR; an assumption for VBR without a header.
        "audio/mpeg" -> "Bitrate promedio" to "$kbps (aprox.)"
        else -> "Bitrate" to "$kbps (declarado)"
    }
}

private fun codecName(mime: String?, codecs: String?): String? = when (mime) {
    null -> null
    "audio/mpeg" -> "MP3 (MPEG Layer III)"
    "audio/mpeg-L2" -> "MPEG Layer II"
    "audio/mpeg-L1" -> "MPEG Layer I"
    "audio/flac" -> "FLAC"
    "audio/mp4a-latm" -> when {
        codecs == null -> "AAC"
        codecs.startsWith("mp4a.40.2") -> "AAC-LC"
        codecs.startsWith("mp4a.40.5") -> "HE-AAC"
        codecs.startsWith("mp4a.40.29") -> "HE-AAC v2"
        else -> "AAC"
    }
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/alac" -> "ALAC (Apple Lossless)"
    "audio/raw" -> "PCM"
    "audio/ac3" -> "Dolby Digital (AC-3)"
    "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
    "audio/3gpp" -> "AMR-NB"
    "audio/amr-wb" -> "AMR-WB"
    else -> mime
}

private fun containerName(mime: String?): String? = when (mime) {
    null -> null
    "audio/mpeg" -> "MPEG audio (MP3)"
    "audio/flac" -> "FLAC"
    "audio/mp4", "video/mp4" -> "MP4 / M4A"
    "audio/ogg", "audio/x-ogg" -> "Ogg"
    "audio/wav", "audio/x-wav" -> "WAV (RIFF)"
    "audio/x-matroska", "audio/webm", "video/webm", "video/x-matroska" -> "Matroska / WebM"
    "audio/aac", "audio/x-aac" -> "ADTS (AAC)"
    "audio/amr" -> "AMR"
    else -> mime
}

@OptIn(UnstableApi::class)
private fun bitDepth(pcmEncoding: Int): String? = when (pcmEncoding) {
    C.ENCODING_PCM_8BIT -> "8 bits"
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16 bits"
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24 bits"
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32 bits"
    C.ENCODING_PCM_FLOAT -> "32 bits (coma flotante)"
    else -> null
}

private fun sampleRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.forLanguageTag("es"), "%.1f kHz", hz / 1000.0)

private fun channels(n: Int): String = when (n) {
    1 -> "1 (mono)"
    2 -> "2 (estéreo)"
    else -> "$n"
}

private fun fileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.forLanguageTag("es"), "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "${(bytes + 512) / 1024} KB"
}

/** m:ss or h:mm:ss. Also used for the playback position. */
internal fun duration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
