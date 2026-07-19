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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** SAF fixture that lives only in the launcher instrumentation APK. */
public final class LauncherFixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.twinquill.test.documents";
    static final String ROOT_ID = "root";
    static final String ONS_UTF8_ROOT_ID = "ons-utf8";
    static final String ONS_GBK_ROOT_ID = "ons-gbk";
    static final String ONS_SJIS_ROOT_ID = "ons-sjis";

    private static final byte[] UTF8_SCRIPT = script(
        "UTF-8 中文測試",
        StandardCharsets.UTF_8
    );
    private static final byte[] GBK_SCRIPT = script(
        "GBK 简体中文测试",
        Charset.forName("GBK")
    );
    private static final byte[] SJIS_SCRIPT = script(
        "Shift-JIS 日本語テスト",
        Charset.forName("Shift_JIS")
    );
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

    static Uri treeUri(String rootId) {
        if (!isKnownRoot(rootId)) {
            throw new IllegalArgumentException("Unknown fixture root");
        }
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId);
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
        if (!isKnownRoot(parentDocumentId)) {
            throw new FileNotFoundException(parentDocumentId);
        }
        MatrixCursor result = documentCursor(projection);
        addDocument(result, scriptId(parentDocumentId));
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        requireKnown(documentId);
        return isKnownRoot(documentId)
            ? DocumentsContract.Document.MIME_TYPE_DIR
            : "text/plain";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return isKnownRoot(parentDocumentId)
            && scriptId(parentDocumentId).equals(documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        byte[] contents = scriptBytes(documentId);
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
                    output.write(contents);
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
        boolean root = isKnownRoot(documentId);
        byte[] contents = root ? null : scriptBytes(documentId);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(documentId);
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add(
                        root
                            ? rootDisplayName(documentId)
                            : "0.txt"
                    );
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(
                        root
                            ? DocumentsContract.Document.MIME_TYPE_DIR
                            : "text/plain"
                    );
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(root ? 0L : contents.length);
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
        if (!isKnownRoot(documentId) && rootForScript(documentId) == null) {
            throw new FileNotFoundException(documentId);
        }
    }

    private static boolean isKnownRoot(String documentId) {
        return ROOT_ID.equals(documentId)
            || ONS_UTF8_ROOT_ID.equals(documentId)
            || ONS_GBK_ROOT_ID.equals(documentId)
            || ONS_SJIS_ROOT_ID.equals(documentId);
    }

    private static String scriptId(String rootId) {
        return rootId + "-script";
    }

    private static String rootDisplayName(String rootId) {
        return ROOT_ID.equals(rootId)
            ? "持久化测试"
            : "TwinQuill ONS 测试";
    }

    private static String rootForScript(String documentId) {
        for (String rootId : new String[] {
            ROOT_ID,
            ONS_UTF8_ROOT_ID,
            ONS_GBK_ROOT_ID,
            ONS_SJIS_ROOT_ID
        }) {
            if (scriptId(rootId).equals(documentId)) {
                return rootId;
            }
        }
        return null;
    }

    private static byte[] scriptBytes(String documentId)
        throws FileNotFoundException {
        String rootId = rootForScript(documentId);
        if (rootId == null) {
            throw new FileNotFoundException(documentId);
        }
        switch (rootId) {
            case ONS_GBK_ROOT_ID:
                return GBK_SCRIPT;
            case ONS_SJIS_ROOT_ID:
                return SJIS_SCRIPT;
            case ROOT_ID:
            case ONS_UTF8_ROOT_ID:
                return UTF8_SCRIPT;
            default:
                throw new FileNotFoundException(documentId);
        }
    }

    private static byte[] script(String caption, Charset encoding) {
        return (
            "*define\n"
                + "game\n"
                + "*start\n"
                + "caption \"" + caption + "\"\n"
                + "end\n"
        ).getBytes(encoding);
    }
}
