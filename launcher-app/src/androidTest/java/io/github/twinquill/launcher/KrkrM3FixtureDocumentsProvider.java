/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only, pipe-backed M3 fixture tree, including real nested directories. */
public final class KrkrM3FixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.github.twinquill.test.m3.documents";
    private static final Set<String> ROOTS = Set.of("m3-loose", "m3-compressed", "m3-patches",
        "m3-cp932", "m3-save", "m3-vectors", "m3-large", "m3-revoke", "m3-many", "m3-broker-compressed", "m3-broker-save");
    private final ConcurrentHashMap<String, Long> sizes = new ConcurrentHashMap<>();

    public static Uri treeUri(String root) {
        if (!ROOTS.contains(root)) throw new IllegalArgumentException("Unknown M3 fixture root");
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, root);
    }
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String root : ROOTS) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (DocumentsContract.Root.COLUMN_FLAGS.equals(column)) row.add(0);
                else row.add(root);
            }
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = cursor(projection);
        add(cursor, id);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor cursor = cursor(projection);
        try {
            check(id);
            if (id.equals("m3-many")) {
                for(int i=0;i<4097;++i)add(cursor,id+"/file-"+i);
                return cursor;
            }
            String[] children = id.equals("m3-large") ? new String[] {"startup.tjs", "oversized.bin"}
                : getContext().getAssets().list(id);
            for (String name : children) add(cursor, id + "/" + name);
            return cursor;
        } catch (IOException error) { throw missing(error); }
    }
    @Override public String getDocumentType(String id) throws FileNotFoundException {
        try { return directory(id) ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"; }
        catch (IOException error) { throw missing(error); }
    }
    @Override public boolean isChildDocument(String parent, String child) {
        return child.startsWith(parent + "/") && child.indexOf("..") < 0;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new UnsupportedOperationException("M3 game tree is read-only");
        try {
            check(id);
            if (directory(id)) throw new FileNotFoundException(id);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (id.equals("m3-large/startup.tjs")) {
                        output.write(largeStartup());
                    } else if (id.equals("m3-large/oversized.bin")) {
                        byte[] buffer = new byte[65536];
                        for (int i = 0; i < 2080; ++i) output.write(buffer);
                    } else {
                        try (InputStream input = getContext().getAssets().open(id)) {
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                        }
                    }
                } catch (IOException closedReader) { /* Expected after bounds rejection or revocation. */ }
            }, "TwinQuill-M3-Fixture");
            writer.start();
            return pipe[0];
        } catch (IOException error) { throw missing(error); }
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS } : projection);
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        try {
            boolean directory = directory(id);
            long length = directory ? 0 : length(id);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(id.substring(id.lastIndexOf('/') + 1)); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add(directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                    case DocumentsContract.Document.COLUMN_SIZE: row.add(length); break;
                    case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row.add(1L); break;
                    case DocumentsContract.Document.COLUMN_FLAGS: row.add(0); break;
                    default: row.add(null);
                }
            }
        } catch (IOException error) { throw missing(error); }
    }
    private boolean directory(String id) throws IOException {
        check(id);
        if (ROOTS.contains(id)) return true;
        if (id.startsWith("m3-large/") || id.startsWith("m3-many/")) return false;
        return getContext().getAssets().list(id).length > 0;
    }
    private long length(String id) throws IOException {
        if (id.startsWith("m3-many/")) return 1;
        if (id.equals("m3-large/startup.tjs")) return largeStartup().length;
        if (id.equals("m3-large/oversized.bin")) return -1; // Provider has unknown size.
        Long known = sizes.get(id);
        if (known != null) return known;
        long length = 0;
        try (InputStream input = getContext().getAssets().open(id)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) length += count;
        }
        sizes.put(id, length);
        return length;
    }
    private static byte[] largeStartup() {
        return "Storages.readBytes('oversized.bin',0,1);".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private void check(String id) throws FileNotFoundException {
        String root = id.substring(0, id.indexOf('/') < 0 ? id.length() : id.indexOf('/'));
        if (!ROOTS.contains(root) || id.contains("..") || id.contains("\\") || id.contains("//"))
            throw new FileNotFoundException(id);
    }
    private static FileNotFoundException missing(IOException error) {
        FileNotFoundException wrapped = new FileNotFoundException(error.getMessage());
        wrapped.initCause(error);
        return wrapped;
    }
}
