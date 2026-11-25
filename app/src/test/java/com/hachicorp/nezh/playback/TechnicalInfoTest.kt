package com.hachicorp.nezh.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalInfoTest {

    private fun Map<String, String>.at(key: String) = this[key]

    @Test
    fun streamAndFileInformationStaySeparateAndUnknownValuesAreLeftOut() {
        val format = Format.Builder()
            .setSampleMimeType("audio/flac")
            .setContainerMimeType("audio/flac")
            .setSampleRate(96_000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build()
        val info = technicalInfo(format, "Canción.flac", 34_500_000, 245_000)
        val stream = info.stream.toMap()
        val file = info.file.toMap()
        assertEquals("FLAC", stream.at("Códec"))
        assertEquals("FLAC", stream.at("Contenedor"))
        assertEquals("96 kHz", stream.at("Frecuencia de muestreo"))
        assertEquals("24 bits", stream.at("Profundidad de bits"))
        assertEquals("2 (estéreo)", stream.at("Canales"))
        assertFalse("no bitrate known: not shown", stream.keys.any { it.startsWith("Bitrate") })
        assertEquals("FLAC", file.at("Formato del archivo"))
        assertEquals("32,9 MB", file.at("Tamaño"))
        assertEquals("4:05", file.at("Duración"))
    }

    @Test
    fun codecAndContainerAreNotConfused() {
        val aac = Format.Builder().setSampleMimeType("audio/mp4a-latm").setCodecs("mp4a.40.2")
            .setContainerMimeType("audio/mp4").setSampleRate(44_100).setChannelCount(1).setAverageBitrate(256_000).build()
        val stream = technicalInfo(aac, "x.m4a", null, null).stream.toMap()
        assertEquals("AAC-LC", stream.at("Códec"))
        assertEquals("MP4 / M4A", stream.at("Contenedor"))
        assertEquals("44.1 kHz".replace('.', ','), stream.at("Frecuencia de muestreo"))
        assertEquals("1 (mono)", stream.at("Canales"))
        assertEquals("256 kbps (calculado)", stream.at("Bitrate promedio"))
        assertEquals("mp4a.40.2", stream.at("Códec (identificador)"))
        assertFalse("bit depth of a compressed stream is unknown", "Profundidad de bits" in stream)
    }

    @Test
    fun bitrateIsLabelledByItsSource() {
        fun labels(container: String, average: Int = Format.NO_VALUE, peak: Int = Format.NO_VALUE) =
            technicalInfo(
                Format.Builder().setContainerMimeType(container).setAverageBitrate(average).setPeakBitrate(peak).build(),
                null, null, null,
            ).stream.toMap().filterKeys { it.startsWith("Bitrate") }
        assertEquals(mapOf("Bitrate promedio" to "320 kbps (aprox.)"), labels("audio/mpeg", 320_000))
        assertEquals(mapOf("Bitrate" to "160 kbps (declarado)"), labels("audio/ogg", 160_000))
        // A declared peak is not the bitrate the file uses: without an average, nothing is shown.
        assertEquals(emptyMap<String, String>(), labels("audio/mp4", peak = 320_000))
    }

    @Test
    fun nothingKnownGivesAnEmptyResult() {
        assertTrue(technicalInfo(null, null, null, null).isEmpty)
        assertEquals(emptyList<Pair<String, String>>(), technicalInfo(Format.Builder().build(), "noextension", 0, 0).stream)
    }

    @Test
    fun durationsAreFormatted() {
        assertEquals("0:00", duration(0))
        assertEquals("0:59", duration(59_999))
        assertEquals("3:07", duration(187_000))
        assertEquals("1:02:03", duration(3_723_000))
    }
}
