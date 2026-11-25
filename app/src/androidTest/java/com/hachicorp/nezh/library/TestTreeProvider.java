package com.hachicorp.nezh.library;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Controllable SAF-like provider for library index tests. Runs in its own process of the test
 * APK (Java: that process has no Kotlin runtime). Document ids are paths ("lib/Rock/a.mp3").
 *
 * <p>The test sets the content with {@code call(METHOD_SET, spec)}, one entry per line:
 * <ul>
 *   <li>{@code path|size|lastModified[|asset]} – a file (size/date may be "-" for "not reported");
 *       with an asset (e.g. {@code formats/mp3_cbr_tagged.mp3}) the file has real content, served
 *       by {@link #openFile} like a SAF provider serves a document;</li>
 *   <li>{@code !fail:path} – listing this directory throws (unreadable);</li>
 *   <li>{@code !deny:path} – listing this directory throws SecurityException.</li>
 * </ul>
 * Directories exist implicitly. {@code call(METHOD_DELAY, millis)} slows every listing down.
 * Nothing is stored on disk.
 */
public class TestTreeProvider extends ContentProvider {

    public static final String AUTHORITY = "com.hachicorp.nezh.test.tree";
    public static final String METHOD_SET = "set";
    public static final String METHOD_DELAY = "delay";

    private static final Object LOCK = new Object();
    private static final Map<String, long[]> FILES = new TreeMap<>(); // path -> {size, mtime} (-1 = null)
    private static final Set<String> FAILING = new HashSet<>();
    private static final Set<String> DENIED = new HashSet<>();
    private static final Map<String, String> ASSETS = new HashMap<>(); // path -> asset name
    private static volatile long delayMs = 0;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_DELAY.equals(method)) {
            delayMs = Long.parseLong(arg);
            return Bundle.EMPTY;
        }
        if (!METHOD_SET.equals(method)) throw new IllegalArgumentException("Unknown method");
        synchronized (LOCK) {
            FILES.clear();
            FAILING.clear();
            DENIED.clear();
            ASSETS.clear();
            for (String raw : arg.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("!fail:")) {
                    FAILING.add(line.substring(6));
                } else if (line.startsWith("!deny:")) {
                    DENIED.add(line.substring(6));
                } else {
                    String[] parts = line.split("\\|");
                    long size = parts.length > 1 && !parts[1].equals("-") ? Long.parseLong(parts[1]) : -1;
                    long date = parts.length > 2 && !parts[2].equals("-") ? Long.parseLong(parts[2]) : -1;
                    FILES.put(parts[0], new long[] {size, date});
                    if (parts.length > 3) ASSETS.put(parts[0], parts[3]);
                }
            }
        }
        return Bundle.EMPTY;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> segments = uri.getPathSegments(); // tree, <tree>, document, <doc>, children
        if (segments.size() != 5 || !"children".equals(segments.get(4))) {
            throw new IllegalArgumentException("Unsupported query");
        }
        if (delayMs > 0) SystemClock.sleep(delayMs);
        String dir = segments.get(3);
        String[] columns = projection != null ? projection
                : new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        MatrixCursor cursor = new MatrixCursor(columns);
        synchronized (LOCK) {
            if (DENIED.contains(dir)) throw new SecurityException("Permission denied (test)");
            if (FAILING.contains(dir)) throw new IllegalStateException("Unreadable directory (test)");
            Map<String, long[]> children = new TreeMap<>();
            Set<String> directories = new HashSet<>();
            String prefix = dir + "/";
            for (Map.Entry<String, long[]> entry : FILES.entrySet()) {
                String path = entry.getKey();
                if (!path.startsWith(prefix)) continue;
                String rest = path.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash < 0) children.put(path, entry.getValue());
                else directories.add(prefix + rest.substring(0, slash));
            }
            if (children.isEmpty() && directories.isEmpty() && !isKnownDirectory(dir)) {
                throw new IllegalStateException("No such directory (test)");
            }
            for (String d : directories) add(cursor, columns, d, Document.MIME_TYPE_DIR, -1, -1);
            for (Map.Entry<String, long[]> e : children.entrySet()) {
                String path = e.getKey();
                String mime = path.endsWith(".mp3") ? "audio/mpeg"
                        : path.endsWith(".flac") ? "audio/flac"
                        : path.endsWith(".m4a") ? "audio/mp4" : "application/octet-stream";
                add(cursor, columns, path, mime, e.getValue()[0], e.getValue()[1]);
            }
        }
        return cursor;
    }

    /** A directory with no listed entries exists if some failing/denied marker or file references it. */
    private static boolean isKnownDirectory(String dir) {
        for (String path : FILES.keySet()) if (path.startsWith(dir + "/")) return true;
        return FAILING.contains(dir) || DENIED.contains(dir) || !dir.contains("/");
    }

    private static void add(MatrixCursor cursor, String[] columns, String id, String mime, long size, long date) {
        String name = id.substring(id.lastIndexOf('/') + 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i] = name; break;
                case Document.COLUMN_MIME_TYPE: row[i] = mime; break;
                case Document.COLUMN_SIZE: row[i] = size >= 0 ? size : null; break;
                case Document.COLUMN_LAST_MODIFIED: row[i] = date >= 0 ? date : null; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
    }

    /** Serves the asset mapped to the document (read-only), as a SAF provider would. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String documentId = DocumentsContract.getDocumentId(uri);
        String asset;
        synchronized (LOCK) {
            asset = ASSETS.get(documentId);
        }
        if (asset == null) throw new FileNotFoundException("No content for this document (test)");
        File file = new File(getContext().getCacheDir(), "tree-" + asset.replace('/', '_'));
        try {
            if (!file.isFile()) {
                try (InputStream in = getContext().getAssets().open(asset); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot prepare content (test)");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
