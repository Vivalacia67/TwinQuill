/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.nativevfs;

import android.content.Context;
import android.net.Uri;

import java.nio.charset.StandardCharsets;

/** Java facade for the C ABI used by TwinQuill's native engines. */
public final class NativeVfs {
    public static final int OPEN_READ = 1;
    public static final int SEEK_SET = 0;
    public static final int SEEK_CUR = 1;
    public static final int SEEK_END = 2;

    static {
        System.loadLibrary("twinquill_native_vfs");
    }

    private NativeVfs() {
    }

    public static synchronized void install(Context context) {
        SafVfsBackend.install(context.getApplicationContext());
        nativeInstall();
    }

    public static long open(Uri treeUri, String relativePath) {
        return nativeOpen(bytes(treeUri.toString()), bytes(relativePath), OPEN_READ);
    }

    /**
     * Opens a seekable read-only OS descriptor for native code that still uses
     * {@code fdopen}. The caller owns the returned descriptor and must close it.
     */
    public static int openReadOnlyDescriptor(Uri treeUri, String relativePath) {
        return SafVfsBackend.detachReadOnlyDescriptor(
            bytes(treeUri.toString()),
            bytes(relativePath)
        );
    }

    public static int read(long handle, byte[] output, int offset, int size) {
        if (offset < 0 || size < 0 || offset > output.length - size) {
            return -5;
        }
        return nativeRead(handle, output, offset, size);
    }

    public static long seek(long handle, long offset, int whence) {
        return nativeSeek(handle, offset, whence);
    }

    public static int close(long handle) {
        return nativeClose(handle);
    }

    public static long[] stat(Uri treeUri, String relativePath) {
        return nativeStat(bytes(treeUri.toString()), bytes(relativePath));
    }

    public static String[] list(Uri treeUri, String relativePath) {
        byte[][] encoded = nativeList(bytes(treeUri.toString()), bytes(relativePath));
        if (encoded == null) {
            return null;
        }
        String[] names = new String[encoded.length];
        for (int index = 0; index < encoded.length; index++) {
            names[index] = new String(encoded[index], StandardCharsets.UTF_8);
        }
        return names;
    }

    public static int mkdir(Uri treeUri, String relativePath) {
        return nativeMkdir(bytes(treeUri.toString()), bytes(relativePath));
    }

    public static int rename(Uri treeUri, String relativePath, String newName) {
        return nativeRename(
            bytes(treeUri.toString()),
            bytes(relativePath),
            bytes(newName)
        );
    }

    public static int delete(Uri treeUri, String relativePath) {
        return nativeDelete(bytes(treeUri.toString()), bytes(relativePath));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static native void nativeInstall();
    private static native long nativeOpen(byte[] treeUri, byte[] path, int flags);
    private static native int nativeRead(
        long handle,
        byte[] output,
        int offset,
        int size
    );
    private static native long nativeSeek(long handle, long offset, int whence);
    private static native int nativeClose(long handle);
    private static native long[] nativeStat(byte[] treeUri, byte[] path);
    private static native byte[][] nativeList(byte[] treeUri, byte[] path);
    private static native int nativeMkdir(byte[] treeUri, byte[] path);
    private static native int nativeRename(byte[] treeUri, byte[] path, byte[] newName);
    private static native int nativeDelete(byte[] treeUri, byte[] path);
}
