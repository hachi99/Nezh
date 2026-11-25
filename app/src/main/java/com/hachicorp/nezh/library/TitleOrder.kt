package com.hachicorp.nezh.library

import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** A run of consecutive tracks under one alphabetical separator. */
data class TrackSection(
    /** "A"–"Z", or [OTHER] for titles that do not start with a Latin letter. */
    val label: String,
    /** Index of the section's first track in the sorted list. */
    val start: Int,
    val size: Int,
) {
    companion object {
        const val OTHER = "#"
    }
}

/**
 * Library order: by title, A–Z, then [TrackSection.OTHER] (digits, symbols and other scripts).
 *
 * - Sections come first, so every section is one contiguous run and never repeats.
 * - Within a section, titles are compared with the device locale's collator at secondary strength:
 *   case is ignored and accents are a secondary difference ("e" < "é" < "f"), as in a dictionary.
 * - Leading punctuation and spaces are skipped for sorting and grouping ("¿Qué?" goes under Q).
 * - Ties (same title ignoring case) are broken by the exact title, then the relative path, then the
 *   track id, so the order is total and deterministic. Metadata is never modified.
 */
object TitleOrder {

    /** The separator a title belongs to. */
    fun sectionOf(title: String): String {
        val first = sortTitle(title).firstOrNull() ?: return TrackSection.OTHER
        // Base letter without diacritics: "É" → "E", "Ñ" → "N".
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
        return if (base in 'A'..'Z') base.toString() else TrackSection.OTHER
    }

    /** Sorts [tracks] (ids are unique) and returns them with their sections. */
    fun sort(tracks: List<Track>, locale: Locale = Locale.getDefault()): Pair<List<Track>, List<TrackSection>> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val keys = tracks.map { SortKey(it, collator) }.sortedWith(SortKey.ORDER)
        val sorted = keys.map { it.track }
        val sections = ArrayList<TrackSection>()
        var start = 0
        while (start < keys.size) {
            val label = keys[start].section
            var end = start + 1
            while (end < keys.size && keys[end].section == label) end++
            sections += TrackSection(label, start, end - start)
            start = end
        }
        return sorted to sections
    }

    private fun sortTitle(title: String): String = title.trimStart { !it.isLetterOrDigit() }

    private class SortKey(val track: Track, collator: Collator) {
        val section = sectionOf(track.title)
        val rank = if (section == TrackSection.OTHER) 26 else section[0] - 'A'
        val key: CollationKey = collator.getCollationKey(sortTitle(track.title))

        companion object {
            val ORDER: Comparator<SortKey> = compareBy<SortKey> { it.rank }
                .thenBy { it.key }
                .thenBy { it.track.title }
                .thenBy { it.track.relativePath }
                .thenBy { it.track.id }
        }
    }
}
