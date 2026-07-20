/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.nativevfs;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Read-only SAF fixture provider compiled only into the instrumentation APK. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.twinquill.nativevfs.test.documents";
    static final String ROOT_ID = "root";
    static final long LARGE_SIZE = 4L * 1024L * 1024L * 1024L + 33L;
    static final int CLOUD_SIZE = 1024 * 1024;

    private static final String UNICODE_ID = "unicode";
    private static final String CLOUD_ID = "cloud";
    private static final String LARGE_ID = "large";
    private static final String REVOKED_ID = "revoked";
    private static final String CASE_DIRECTORY_ID = "case-directory";
    private static final String CASE_IMAGE_ID = "case-image";
    private static final String AMBIGUOUS_LOWER_ID = "ambiguous-lower";
    private static final String AMBIGUOUS_UPPER_ID = "ambiguous-upper";

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String[] DEFAULT_ROOT_PROJECTION = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    private File unicodeFile;
    private File largeFile;
    private File caseImageFile;
    private File ambiguousLowerFile;
    private File ambiguousUpperFile;

    @Override
    public boolean onCreate() {
        File directory = new File(getContext().getCacheDir(), "fixture-documents");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return false;
        }
        unicodeFile = new File(directory, "unicode.txt");
        largeFile = new File(directory, "large.bin");
        caseImageFile = new File(directory, "case-image.txt");
        ambiguousLowerFile = new File(directory, "ambiguous-lower.txt");
        ambiguousUpperFile = new File(directory, "ambiguous-upper.txt");
        try {
            try (FileOutputStream output = new FileOutputStream(unicodeFile)) {
                output.write("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            }
            writeText(caseImageFile, "case-image");
            writeText(ambiguousLowerFile, "lower");
            writeText(ambiguousUpperFile, "upper");
            try (RandomAccessFile output = new RandomAccessFile(largeFile, "rw")) {
                output.setLength(LARGE_SIZE);
                output.seek(LARGE_SIZE - 1);
                output.write(0x5a);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(
            projection == null ? DEFAULT_ROOT_PROJECTION : projection
        );
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_TITLE:
                    row.add("TwinQuill VFS fixtures");
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
        if (CASE_DIRECTORY_ID.equals(parentDocumentId)) {
            MatrixCursor result = documentCursor(projection);
            addDocument(result, CASE_IMAGE_ID);
            return result;
        }
        if (!ROOT_ID.equals(parentDocumentId)) {
            throw new FileNotFoundException(parentDocumentId);
        }
        MatrixCursor result = documentCursor(projection);
        addDocument(result, UNICODE_ID);
        addDocument(result, CLOUD_ID);
        addDocument(result, LARGE_ID);
        addDocument(result, REVOKED_ID);
        addDocument(result, CASE_DIRECTORY_ID);
        addDocument(result, AMBIGUOUS_LOWER_ID);
        addDocument(result, AMBIGUOUS_UPPER_ID);
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        requireKnown(documentId);
        return ROOT_ID.equals(documentId) || CASE_DIRECTORY_ID.equals(documentId)
            ? DocumentsContract.Document.MIME_TYPE_DIR
            : "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (ROOT_ID.equals(parentDocumentId)) {
            return !ROOT_ID.equals(documentId) && isKnown(documentId);
        }
        return CASE_DIRECTORY_ID.equals(parentDocumentId)
            && CASE_IMAGE_ID.equals(documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("Fixture provider is read-only");
        }
        if (REVOKED_ID.equals(documentId)) {
            throw new SecurityException("Fixture permission was revoked");
        }
        if (UNICODE_ID.equals(documentId)) {
            return ParcelFileDescriptor.open(
                unicodeFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            );
        }
        if (CASE_IMAGE_ID.equals(documentId)) {
            return readOnly(caseImageFile);
        }
        if (AMBIGUOUS_LOWER_ID.equals(documentId)) {
            return readOnly(ambiguousLowerFile);
        }
        if (AMBIGUOUS_UPPER_ID.equals(documentId)) {
            return readOnly(ambiguousUpperFile);
        }
        if (LARGE_ID.equals(documentId)) {
            return ParcelFileDescriptor.open(
                largeFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            );
        }
        if (CLOUD_ID.equals(documentId)) {
            return openCloudPipe();
        }
        throw new FileNotFoundException(documentId);
    }

    private ParcelFileDescriptor openCloudPipe() throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                ) {
                    byte[] buffer = new byte[64 * 1024];
                    int offset = 0;
                    while (offset < CLOUD_SIZE) {
                        int count = Math.min(buffer.length, CLOUD_SIZE - offset);
                        for (int index = 0; index < count; index++) {
                            buffer[index] = cloudByte(offset + index);
                        }
                        output.write(buffer, 0, count);
                        offset += count;
                    }
                } catch (IOException ignored) {
                    // A closed reader is an expected cancellation path.
                }
            }, "TwinQuill-VFS-fixture");
            writer.start();
            return pipe[0];
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    static byte cloudByte(int offset) {
        return (byte) ((offset * 31 + 7) & 0xff);
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ParcelFileDescriptor readOnly(File file)
        throws FileNotFoundException {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(
            projection == null ? DEFAULT_DOCUMENT_PROJECTION : projection
        );
    }

    private void addDocument(MatrixCursor cursor, String documentId)
        throws FileNotFoundException {
        requireKnown(documentId);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(documentId);
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add(displayName(documentId));
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(getDocumentType(documentId));
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(size(documentId));
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

    private static String displayName(String documentId) {
        switch (documentId) {
            case ROOT_ID:
                return "root";
            case UNICODE_ID:
                return "中文_日本語.txt";
            case CLOUD_ID:
                return "cloud.bin";
            case LARGE_ID:
                return "large.bin";
            case REVOKED_ID:
                return "revoked.bin";
            case CASE_DIRECTORY_ID:
                return "bg";
            case CASE_IMAGE_ID:
                return "b27a.png";
            case AMBIGUOUS_LOWER_ID:
                return "choice.png";
            case AMBIGUOUS_UPPER_ID:
                return "CHOICE.PNG";
            default:
                throw new IllegalArgumentException(documentId);
        }
    }

    private long size(String documentId) {
        switch (documentId) {
            case ROOT_ID:
            case CASE_DIRECTORY_ID:
                return 0L;
            case UNICODE_ID:
                return unicodeFile.length();
            case CLOUD_ID:
                return CLOUD_SIZE;
            case LARGE_ID:
                return largeFile.length();
            case REVOKED_ID:
                return 1L;
            case CASE_IMAGE_ID:
                return caseImageFile.length();
            case AMBIGUOUS_LOWER_ID:
                return ambiguousLowerFile.length();
            case AMBIGUOUS_UPPER_ID:
                return ambiguousUpperFile.length();
            default:
                throw new IllegalArgumentException(documentId);
        }
    }

    private static void requireKnown(String documentId) throws FileNotFoundException {
        if (!isKnown(documentId)) {
            throw new FileNotFoundException(documentId);
        }
    }

    private static boolean isKnown(String documentId) {
        return ROOT_ID.equals(documentId)
            || UNICODE_ID.equals(documentId)
            || CLOUD_ID.equals(documentId)
            || LARGE_ID.equals(documentId)
            || REVOKED_ID.equals(documentId)
            || CASE_DIRECTORY_ID.equals(documentId)
            || CASE_IMAGE_ID.equals(documentId)
            || AMBIGUOUS_LOWER_ID.equals(documentId)
            || AMBIGUOUS_UPPER_ID.equals(documentId);
    }
}
