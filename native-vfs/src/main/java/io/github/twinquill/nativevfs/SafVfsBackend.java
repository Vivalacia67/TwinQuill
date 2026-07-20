/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.nativevfs;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Package-private SAF implementation called by the native C bridge. */
final class SafVfsBackend {
    private static final int ERROR = -1;
    private static final int PERMISSION = -2;
    private static final int NOT_FOUND = -3;
    private static final int UNSUPPORTED = -4;
    private static final int INVALID = -5;

    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String[] CHILD_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);
    private static final Map<Long, OpenHandle> OPEN_HANDLES = new ConcurrentHashMap<>();

    private static volatile ContentResolver resolver;
    private static volatile File cacheDirectory;

    private SafVfsBackend() {
    }

    static void install(Context context) {
        resolver = context.getContentResolver();
        File directory = new File(context.getCacheDir(), "native-vfs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create VFS cache directory");
        }
        cacheDirectory = directory;
    }

    static long open(byte[] treeBytes, byte[] pathBytes, int flags) {
        if (resolver == null || flags != 1) {
            return resolver == null ? INVALID : UNSUPPORTED;
        }
        try {
            Uri treeUri = parseTree(treeBytes);
            Uri documentUri = resolve(treeUri, text(pathBytes), false, true);
            if (documentUri == null) {
                return NOT_FOUND;
            }
            OpenHandle handle = openSeekableRead(documentUri);
            long id = NEXT_HANDLE.getAndIncrement();
            OPEN_HANDLES.put(id, handle);
            return id;
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (FileNotFoundException exception) {
            return NOT_FOUND;
        } catch (IllegalArgumentException exception) {
            return INVALID;
        } catch (IOException exception) {
            return ERROR;
        }
    }

    static int detachReadOnlyDescriptor(byte[] treeBytes, byte[] pathBytes) {
        try {
            Uri treeUri = parseTree(treeBytes);
            Uri documentUri = resolve(treeUri, text(pathBytes), false, true);
            if (documentUri == null) {
                return NOT_FOUND;
            }
            return detachSeekableRead(documentUri);
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (FileNotFoundException exception) {
            return NOT_FOUND;
        } catch (IllegalArgumentException exception) {
            return INVALID;
        } catch (IOException exception) {
            return ERROR;
        }
    }

    static int read(long handleId, byte[] output, int size) {
        OpenHandle handle = OPEN_HANDLES.get(handleId);
        if (handle == null || size < 0 || size > output.length) {
            return INVALID;
        }
        try {
            int count = handle.input.read(output, 0, size);
            return count < 0 ? 0 : count;
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (IOException exception) {
            return ERROR;
        }
    }

    static long seek(long handleId, long offset, int whence) {
        OpenHandle handle = OPEN_HANDLES.get(handleId);
        if (handle == null) {
            return INVALID;
        }
        try {
            long base;
            switch (whence) {
                case 0:
                    base = 0;
                    break;
                case 1:
                    base = handle.channel.position();
                    break;
                case 2:
                    base = handle.channel.size();
                    break;
                default:
                    return INVALID;
            }
            if ((offset < 0 && base < -offset) || (offset > 0 && base > Long.MAX_VALUE - offset)) {
                return INVALID;
            }
            long position = base + offset;
            handle.channel.position(position);
            return position;
        } catch (IOException exception) {
            return ERROR;
        }
    }

    static int close(long handleId) {
        OpenHandle handle = OPEN_HANDLES.remove(handleId);
        if (handle == null) {
            return INVALID;
        }
        try {
            handle.close();
            return 0;
        } catch (IOException exception) {
            return ERROR;
        }
    }

    static long[] stat(byte[] treeBytes, byte[] pathBytes) {
        long[] failure = {ERROR, 0, 0, 0, 0, 0};
        try {
            Uri treeUri = parseTree(treeBytes);
            Uri documentUri = resolve(treeUri, text(pathBytes), true, true);
            if (documentUri == null) {
                failure[0] = NOT_FOUND;
                return failure;
            }
            try (Cursor cursor = resolver.query(
                documentUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null
            )) {
                if (cursor == null || !cursor.moveToFirst()) {
                    failure[0] = NOT_FOUND;
                    return failure;
                }
                String mimeType = cursor.getString(1);
                return new long[] {
                    0,
                    1,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? 1 : 0,
                    cursor.isNull(2) ? -1 : cursor.getLong(2),
                    cursor.isNull(3) ? 0 : cursor.getLong(3),
                    cursor.isNull(4) ? 0 : cursor.getLong(4)
                };
            }
        } catch (SecurityException exception) {
            failure[0] = PERMISSION;
        } catch (FileNotFoundException exception) {
            failure[0] = NOT_FOUND;
        } catch (IllegalArgumentException exception) {
            failure[0] = INVALID;
        } catch (RuntimeException exception) {
            failure[0] = ERROR;
        }
        return failure;
    }

    static byte[][] list(byte[] treeBytes, byte[] pathBytes) {
        try {
            Uri treeUri = parseTree(treeBytes);
            Uri directory = resolve(treeUri, text(pathBytes), true, true);
            if (directory == null) {
                return null;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(directory)
            );
            List<String> names = new ArrayList<>();
            try (Cursor cursor = resolver.query(
                children,
                new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null
            )) {
                if (cursor == null) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            byte[][] encoded = new byte[names.size()][];
            for (int index = 0; index < names.size(); index++) {
                encoded[index] = names.get(index).getBytes(StandardCharsets.UTF_8);
            }
            return encoded;
        } catch (SecurityException | FileNotFoundException | IllegalArgumentException exception) {
            return null;
        }
    }

    static int mkdir(byte[] treeBytes, byte[] pathBytes) {
        try {
            PathParts parts = splitParent(text(pathBytes));
            Uri treeUri = parseTree(treeBytes);
            Uri parent = resolve(treeUri, parts.parent, true, false);
            if (parent == null) {
                return NOT_FOUND;
            }
            Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                parts.name
            );
            return created == null ? ERROR : 0;
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (FileNotFoundException exception) {
            return NOT_FOUND;
        } catch (UnsupportedOperationException exception) {
            return UNSUPPORTED;
        } catch (IllegalArgumentException exception) {
            return INVALID;
        }
    }

    static int rename(byte[] treeBytes, byte[] pathBytes, byte[] newNameBytes) {
        try {
            String newName = text(newNameBytes);
            validateName(newName);
            Uri treeUri = parseTree(treeBytes);
            Uri document = resolve(treeUri, text(pathBytes), false, false);
            if (document == null) {
                return NOT_FOUND;
            }
            Uri renamed = DocumentsContract.renameDocument(resolver, document, newName);
            return renamed == null ? ERROR : 0;
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (FileNotFoundException exception) {
            return NOT_FOUND;
        } catch (UnsupportedOperationException exception) {
            return UNSUPPORTED;
        } catch (IllegalArgumentException exception) {
            return INVALID;
        }
    }

    static int delete(byte[] treeBytes, byte[] pathBytes) {
        try {
            Uri treeUri = parseTree(treeBytes);
            Uri document = resolve(treeUri, text(pathBytes), false, false);
            if (document == null) {
                return NOT_FOUND;
            }
            return DocumentsContract.deleteDocument(resolver, document) ? 0 : ERROR;
        } catch (SecurityException exception) {
            return PERMISSION;
        } catch (FileNotFoundException exception) {
            return NOT_FOUND;
        } catch (UnsupportedOperationException exception) {
            return UNSUPPORTED;
        } catch (IllegalArgumentException exception) {
            return INVALID;
        }
    }

    private static OpenHandle openSeekableRead(Uri uri) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) {
            throw new FileNotFoundException(uri.toString());
        }
        FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        try {
            FileChannel channel = input.getChannel();
            channel.position(0);
            return new OpenHandle(null, input, channel, null);
        } catch (IOException notSeekable) {
            input.close();
            File cache = File.createTempFile("tq-vfs-", ".cache", cacheDirectory);
            boolean complete = false;
            try (
                InputStream source = resolver.openInputStream(uri);
                FileOutputStream output = new FileOutputStream(cache)
            ) {
                if (source == null) {
                    throw new FileNotFoundException(uri.toString());
                }
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = source.read(buffer)) >= 0) {
                    if (count > 0) {
                        output.write(buffer, 0, count);
                    }
                }
                complete = true;
            } finally {
                if (!complete) {
                    cache.delete();
                }
            }
            FileInputStream cachedInput = new FileInputStream(cache);
            return new OpenHandle(null, cachedInput, cachedInput.getChannel(), cache);
        }
    }

    private static int detachSeekableRead(Uri uri) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) {
            throw new FileNotFoundException(uri.toString());
        }
        try {
            try {
                Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
                return descriptor.detachFd();
            } catch (ErrnoException notSeekable) {
                if (notSeekable.errno != OsConstants.ESPIPE) {
                    throw new IOException("Unable to inspect provider descriptor", notSeekable);
                }
            }
        } finally {
            descriptor.close();
        }

        File cache = File.createTempFile("tq-vfs-fd-", ".cache", cacheDirectory);
        boolean complete = false;
        try (
            InputStream source = resolver.openInputStream(uri);
            FileOutputStream output = new FileOutputStream(cache)
        ) {
            if (source == null) {
                throw new FileNotFoundException(uri.toString());
            }
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            complete = true;
        } finally {
            if (!complete) {
                cache.delete();
            }
        }

        ParcelFileDescriptor cached = ParcelFileDescriptor.open(
            cache,
            ParcelFileDescriptor.MODE_READ_ONLY
        );
        if (!cache.delete()) {
            cached.close();
            throw new IOException("Unable to unlink VFS descriptor cache");
        }
        return cached.detachFd();
    }

    private static Uri parseTree(byte[] bytes) throws FileNotFoundException {
        if (resolver == null) {
            throw new IllegalStateException("VFS was not installed");
        }
        Uri uri = Uri.parse(text(bytes));
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
            || !DocumentsContract.isTreeUri(uri)) {
            throw new FileNotFoundException("Not a SAF tree URI");
        }
        return uri;
    }

    private static Uri resolve(
        Uri treeUri,
        String relativePath,
        boolean allowRoot,
        boolean caseInsensitiveFallback
    )
        throws FileNotFoundException {
        List<String> segments = pathSegments(relativePath);
        Uri current = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        );
        if (segments.isEmpty()) {
            if (!allowRoot) {
                throw new IllegalArgumentException("A child path is required");
            }
            return current;
        }
        for (String segment : segments) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(current)
            );
            Uri exact = null;
            Uri folded = null;
            boolean foldedAmbiguous = false;
            try (Cursor cursor = resolver.query(
                children,
                CHILD_PROJECTION,
                null,
                null,
                null
            )) {
                if (cursor == null) {
                    throw new FileNotFoundException(segment);
                }
                while (cursor.moveToNext()) {
                    String displayName = cursor.getString(1);
                    if (segment.equals(displayName)) {
                        exact = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(0)
                        );
                        break;
                    }
                    if (caseInsensitiveFallback
                        && displayName != null
                        && segment.equalsIgnoreCase(displayName)) {
                        Uri candidate = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(0)
                        );
                        if (folded == null) {
                            folded = candidate;
                        } else {
                            foldedAmbiguous = true;
                        }
                    }
                }
            }
            Uri found = exact != null
                ? exact
                : (foldedAmbiguous ? null : folded);
            if (found == null) {
                return null;
            }
            current = found;
        }
        return current;
    }

    private static List<String> pathSegments(String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path");
        }
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] raw = normalized.split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            validateName(segment);
            segments.add(segment);
        }
        return segments;
    }

    private static PathParts splitParent(String path) {
        List<String> segments = pathSegments(path);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A child path is required");
        }
        String name = segments.remove(segments.size() - 1);
        return new PathParts(String.join("/", segments), name);
    }

    private static void validateName(String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path segment");
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class PathParts {
        final String parent;
        final String name;

        PathParts(String parent, String name) {
            this.parent = parent;
            this.name = name;
        }
    }

    private static final class OpenHandle implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final FileInputStream input;
        final FileChannel channel;
        final File cacheFile;

        OpenHandle(
            ParcelFileDescriptor descriptor,
            FileInputStream input,
            FileChannel channel,
            File cacheFile
        ) {
            this.descriptor = descriptor;
            this.input = input;
            this.channel = channel;
            this.cacheFile = cacheFile;
        }

        @Override
        public void close() throws IOException {
            try {
                input.close();
            } finally {
                if (descriptor != null) {
                    descriptor.close();
                }
                if (cacheFile != null) {
                    cacheFile.delete();
                }
            }
        }
    }
}
