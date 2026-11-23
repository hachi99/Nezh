package com.hachicorp.nezh.library

/**
 * Identity of a track in the index and in Media3 (media id): `"<authority>::<document id>"`.
 *
 * Why this and not the URI:
 * - SAF guarantees that a document id is unique and stable *within its provider* while the
 *   document exists (DocumentsContract.Document.COLUMN_DOCUMENT_ID). Ids of different providers
 *   can collide, so the authority is part of the identity.
 * - A document URI also embeds the tree it was reached through
 *   (`content://<authority>/tree/<tree id>/document/<document id>`), so the same file found via
 *   nested authorized folders ("Music" and "Music/Rock") has two URIs but one identity. URIs are
 *   kept per source (folder) only to open the file.
 *
 * Limitations (documented, by design):
 * - Path-based providers (e.g. ExternalStorageProvider: "primary:Music/a.mp3") change the id when a
 *   file is renamed or moved. That is handled as a removal plus a new track; two distinct files can
 *   never share an id, but play history tied to the old id would not follow the file.
 * - Providers with opaque ids (e.g. cloud providers) may keep the id across renames; then the track
 *   keeps its identity and only its name/path is updated.
 * - If a provider reuses an id for a different file at the same path (delete + create), the size or
 *   modification date normally differs and the metadata is re-read; when the provider does not
 *   report them, the periodic full reconciliation re-reads it.
 * Content hashes are deliberately not used: reading whole audio files to identify them is too slow.
 */
internal object TrackIdentity {
    private const val SEPARATOR = "::"

    fun of(authority: String, documentId: String): String = "$authority$SEPARATOR$documentId"

    /** Splits an id back into (authority, document id); null if it is not a track id. */
    fun parse(id: String): Pair<String, String>? {
        val index = id.indexOf(SEPARATOR)
        if (index <= 0 || index + SEPARATOR.length >= id.length) return null
        return id.substring(0, index) to id.substring(index + SEPARATOR.length)
    }
}
