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

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** SAF fixture that lives only in the launcher instrumentation APK. */
public final class LauncherFixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.twinquill.test.documents";
    static final String ROOT_ID = "root";
    static final String ONS_UTF8_ROOT_ID = "ons-utf8";
    static final String ONS_GBK_ROOT_ID = "ons-gbk";
    static final String ONS_SJIS_ROOT_ID = "ons-sjis";
    static final String ONS_SAVE_ROOT_ID = "ons-save";
    static final String ONS_NSA_ROOT_ID = "ons-nsa";
    static final String ONS_SAR_ROOT_ID = "ons-sar";

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
    private static final byte[] SAVE_SCRIPT = (
        "*define\n"
            + "game\n"
            + "*start\n"
            + "savefileexist %0,1\n"
            + "if %0=1 goto *restore\n"
            + "mov %1,123\n"
            + "savegame 1\n"
            + "savefileexist %2,2\n"
            + "if %2=0 end\n"
            + "if %1=123 savegame 3\n"
            + "end\n"
            + "*restore\n"
            + "savegame 2\n"
            + "mov %1,999\n"
            + "loadgame 1\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] NSA_SCRIPT = archiveScript("nsa");
    private static final byte[] SAR_SCRIPT = archiveScript("sar");
    private static final byte[] BITMAP = bitmap();
    private static final byte[] NSA_ARCHIVE = archive(BITMAP, true);
    private static final byte[] SAR_ARCHIVE = archive(BITMAP, false);
    private static final AtomicInteger NSA_ARCHIVE_OPENS = new AtomicInteger();
    private static final AtomicInteger SAR_ARCHIVE_OPENS = new AtomicInteger();
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
        if (isArchiveRoot(parentDocumentId)) {
            addDocument(result, archiveId(parentDocumentId));
        }
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        requireKnown(documentId);
        if (isKnownRoot(documentId)) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return rootForArchive(documentId) == null
            ? "text/plain"
            : "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return isKnownRoot(parentDocumentId)
            && (
                scriptId(parentDocumentId).equals(documentId)
                    || (
                        isArchiveRoot(parentDocumentId)
                            && archiveId(parentDocumentId).equals(documentId)
                    )
            );
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        byte[] contents = documentBytes(documentId);
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("Fixture provider is read-only");
        }
        String archiveRoot = rootForArchive(documentId);
        if (archiveRoot != null) {
            archiveOpenCounter(archiveRoot).incrementAndGet();
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
        byte[] contents = root ? null : documentBytes(documentId);
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
                            : documentDisplayName(documentId)
                    );
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(
                        root
                            ? DocumentsContract.Document.MIME_TYPE_DIR
                            : (
                                rootForArchive(documentId) == null
                                    ? "text/plain"
                                    : "application/octet-stream"
                            )
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
        if (!isKnownRoot(documentId)
            && rootForScript(documentId) == null
            && rootForArchive(documentId) == null) {
            throw new FileNotFoundException(documentId);
        }
    }

    private static boolean isKnownRoot(String documentId) {
        return ROOT_ID.equals(documentId)
            || ONS_UTF8_ROOT_ID.equals(documentId)
            || ONS_GBK_ROOT_ID.equals(documentId)
            || ONS_SJIS_ROOT_ID.equals(documentId)
            || ONS_SAVE_ROOT_ID.equals(documentId)
            || ONS_NSA_ROOT_ID.equals(documentId)
            || ONS_SAR_ROOT_ID.equals(documentId);
    }

    private static String scriptId(String rootId) {
        return rootId + "-script";
    }

    private static String archiveId(String rootId) {
        return rootId + "-archive";
    }

    private static boolean isArchiveRoot(String rootId) {
        return ONS_NSA_ROOT_ID.equals(rootId) || ONS_SAR_ROOT_ID.equals(rootId);
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
            ONS_SJIS_ROOT_ID,
            ONS_SAVE_ROOT_ID,
            ONS_NSA_ROOT_ID,
            ONS_SAR_ROOT_ID
        }) {
            if (scriptId(rootId).equals(documentId)) {
                return rootId;
            }
        }
        return null;
    }

    private static String rootForArchive(String documentId) {
        for (String rootId : new String[] {
            ONS_NSA_ROOT_ID,
            ONS_SAR_ROOT_ID
        }) {
            if (archiveId(rootId).equals(documentId)) {
                return rootId;
            }
        }
        return null;
    }

    private static String documentDisplayName(String documentId)
        throws FileNotFoundException {
        String archiveRoot = rootForArchive(documentId);
        if (ONS_NSA_ROOT_ID.equals(archiveRoot)) {
            return "arc.nsa";
        }
        if (ONS_SAR_ROOT_ID.equals(archiveRoot)) {
            return "arc.sar";
        }
        if (rootForScript(documentId) != null) {
            return "0.txt";
        }
        throw new FileNotFoundException(documentId);
    }

    private static byte[] documentBytes(String documentId)
        throws FileNotFoundException {
        String archiveRoot = rootForArchive(documentId);
        if (ONS_NSA_ROOT_ID.equals(archiveRoot)) {
            return NSA_ARCHIVE;
        }
        if (ONS_SAR_ROOT_ID.equals(archiveRoot)) {
            return SAR_ARCHIVE;
        }
        return scriptBytes(documentId);
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
            case ONS_SAVE_ROOT_ID:
                return SAVE_SCRIPT;
            case ONS_NSA_ROOT_ID:
                return NSA_SCRIPT;
            case ONS_SAR_ROOT_ID:
                return SAR_SCRIPT;
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

    static void resetArchiveOpenCount(String rootId) {
        archiveOpenCounter(rootId).set(0);
    }

    static int archiveOpenCount(String rootId) {
        return archiveOpenCounter(rootId).get();
    }

    private static AtomicInteger archiveOpenCounter(String rootId) {
        if (ONS_NSA_ROOT_ID.equals(rootId)) {
            return NSA_ARCHIVE_OPENS;
        }
        if (ONS_SAR_ROOT_ID.equals(rootId)) {
            return SAR_ARCHIVE_OPENS;
        }
        throw new IllegalArgumentException("Not an archive fixture root");
    }

    private static byte[] archiveScript(String command) {
        return (
            "*define\n"
                + command + "\n"
                + "game\n"
                + "*start\n"
                + "bg \"fixture.bmp\",1\n"
                + "fileexist %0,\"fixture.bmp\"\n"
                + "if %0=1 savegame 4\n"
                + "end\n"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] archive(byte[] contents, boolean nsa) {
        byte[] name = "fixture.bmp".getBytes(StandardCharsets.US_ASCII);
        int entryHeaderLength = name.length + 1 + (nsa ? 13 : 8);
        int baseOffset = 6 + entryHeaderLength;
        ByteArrayOutputStream output =
            new ByteArrayOutputStream(baseOffset + contents.length);
        writeBigEndianShort(output, 1);
        writeBigEndianInt(output, baseOffset);
        output.write(name, 0, name.length);
        output.write(0);
        if (nsa) {
            output.write(0);
        }
        writeBigEndianInt(output, 0);
        writeBigEndianInt(output, contents.length);
        if (nsa) {
            writeBigEndianInt(output, contents.length);
        }
        output.write(contents, 0, contents.length);
        return output.toByteArray();
    }

    private static byte[] bitmap() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(58);
        output.write('B');
        output.write('M');
        writeLittleEndianInt(output, 58);
        writeLittleEndianShort(output, 0);
        writeLittleEndianShort(output, 0);
        writeLittleEndianInt(output, 54);
        writeLittleEndianInt(output, 40);
        writeLittleEndianInt(output, 1);
        writeLittleEndianInt(output, 1);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, 24);
        writeLittleEndianInt(output, 0);
        writeLittleEndianInt(output, 4);
        writeLittleEndianInt(output, 2_835);
        writeLittleEndianInt(output, 2_835);
        writeLittleEndianInt(output, 0);
        writeLittleEndianInt(output, 0);
        output.write(0x20);
        output.write(0x80);
        output.write(0xff);
        output.write(0);
        return output.toByteArray();
    }

    private static void writeBigEndianShort(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeBigEndianInt(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeLittleEndianShort(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeLittleEndianInt(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
