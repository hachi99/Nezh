package com.hachicorp.nezh.library;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract.Document;

import java.util.List;
import java.util.Locale;

/**
 * Synthetic document tree answering the same child queries SafTreeScanner issues
 * ({@code content://<authority>/tree/<tree>/document/<doc>/children}).
 *
 * <p>The tree document id is the number of tracks N. Layout, like a typical music folder:
 * artist (100 tracks each) / album (10 per artist) / 10 tracks + cover.jpg. Nothing is stored;
 * every listing is computed from the document id.
 *
 * <p>Written in Java on purpose: it runs in its own process of the test APK, which does not
 * contain the Kotlin standard library (the app APK does).
 */
public class BenchTreeProvider extends ContentProvider {

    public static final String AUTHORITY = "com.hachicorp.nezh.test.benchtree";
    private static final int TRACKS_PER_ARTIST = 100;
    private static final int TRACKS_PER_ALBUM = 10;
    private static final String[] DEFAULT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> segments = uri.getPathSegments(); // tree, <tree>, document, <doc>, children
        if (segments.size() != 5 || !"tree".equals(segments.get(0)) || !"children".equals(segments.get(4))) {
            throw new IllegalArgumentException("Unsupported query");
        }
        String[] columns = projection != null ? projection : DEFAULT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        String documentId = segments.get(3);
        String[] parts = documentId.split("/");
        int total = Integer.parseInt(parts[0]);
        if (parts.length == 1) {
            int artists = ceilDiv(total, TRACKS_PER_ARTIST);
            for (int a = 0; a < artists; a++) {
                add(cursor, columns, documentId + "/a" + a, String.format(Locale.ROOT, "Artista %04d", a), Document.MIME_TYPE_DIR);
            }
        } else if (parts.length == 2) {
            int artist = Integer.parseInt(parts[1].substring(1));
            int tracksForArtist = Math.min(TRACKS_PER_ARTIST, total - artist * TRACKS_PER_ARTIST);
            int albums = ceilDiv(tracksForArtist, TRACKS_PER_ALBUM);
            for (int b = 0; b < albums; b++) {
                add(cursor, columns, documentId + "/b" + b, String.format(Locale.ROOT, "Álbum %02d", b), Document.MIME_TYPE_DIR);
            }
        } else if (parts.length == 3) {
            int artist = Integer.parseInt(parts[1].substring(1));
            int album = Integer.parseInt(parts[2].substring(1));
            int first = artist * TRACKS_PER_ARTIST + album * TRACKS_PER_ALBUM;
            int count = Math.min(TRACKS_PER_ALBUM, total - first);
            for (int t = 0; t < count; t++) {
                add(cursor, columns, documentId + "/t" + t,
                        String.format(Locale.ROOT, "%02d - Canción %05d.mp3", t + 1, first + t), "audio/mpeg");
            }
            add(cursor, columns, documentId + "/cover", "cover.jpg", "image/jpeg");
        }
        return cursor;
    }

    private static void add(MatrixCursor cursor, String[] columns, String id, String name, String mime) {
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i] = name; break;
                case Document.COLUMN_MIME_TYPE: row[i] = mime; break;
                // Like ExternalStorageProvider: size and date are reported (fixed values here).
                case Document.COLUMN_SIZE: row[i] = Document.MIME_TYPE_DIR.equals(mime) ? null : 4_000_000L; break;
                case Document.COLUMN_LAST_MODIFIED: row[i] = 1_700_000_000_000L; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
