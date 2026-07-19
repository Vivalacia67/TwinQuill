/*
 * SPDX-License-Identifier: GPL-2.0-or-later
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
import java.nio.charset.StandardCharsets;

/** SAF fixture that lives only in the launcher instrumentation APK. */
public final class LauncherFixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.twinquill.test.documents";
    static final String ROOT_ID = "root";

    private static final String SCRIPT_ID = "ons-script";
    private static final byte[] SCRIPT = "*define\ngame\n*start\nend\n"
        .getBytes(StandardCharsets.UTF_8);
    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String[] ROOT_PROJECTION = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(
            projection == null ? ROOT_PROJECTION : projection
        );
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_TITLE:
                    row.add("TwinQuill launcher fixtures");
                    break;
                case DocumentsContract.Root.COLUMN_FLAGS:
                    row.add(0);
                    break;
                case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES:
                    row.add(Long.MAX_VALUE);
                    break;
                default:
                    row.add(null);
                    break;
            }
        }
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
        throws FileNotFoundException {
        MatrixCursor result = documentCursor(projection);
        addDocument(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
        String parentDocumentId,
        String[] projection,
        String sortOrder
    ) throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) {
            throw new FileNotFoundException(parentDocumentId);
        }
        MatrixCursor result = documentCursor(projection);
        addDocument(result, SCRIPT_ID);
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        requireKnown(documentId);
        return ROOT_ID.equals(documentId)
            ? DocumentsContract.Document.MIME_TYPE_DIR
            : "text/plain";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && SCRIPT_ID.equals(documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        if (!SCRIPT_ID.equals(documentId)) {
            throw new FileNotFoundException(documentId);
        }
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("Fixture provider is read-only");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                ) {
                    output.write(SCRIPT);
                } catch (IOException ignored) {
                    // Closing a reader early is a valid cancellation path.
                }
            }, "TwinQuill-launcher-fixture");
            writer.start();
            return pipe[0];
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    private static MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(
            projection == null ? DOCUMENT_PROJECTION : projection
        );
    }

    private static void addDocument(MatrixCursor cursor, String documentId)
        throws FileNotFoundException {
        requireKnown(documentId);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(documentId);
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add(ROOT_ID.equals(documentId) ? "持久化测试" : "0.txt");
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(
                        ROOT_ID.equals(documentId)
                            ? DocumentsContract.Document.MIME_TYPE_DIR
                            : "text/plain"
                    );
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(ROOT_ID.equals(documentId) ? 0L : SCRIPT.length);
                    break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                    row.add(0L);
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row.add(0);
                    break;
                default:
                    row.add(null);
                    break;
            }
        }
    }

    private static void requireKnown(String documentId) throws FileNotFoundException {
        if (!ROOT_ID.equals(documentId) && !SCRIPT_ID.equals(documentId)) {
            throw new FileNotFoundException(documentId);
        }
    }
}
