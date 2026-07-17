/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded probe for raw, unprotected XP3 indexes used by the M1 detector. */
final class Xp3ArchiveProbe {
    private static final byte[] XP3_MARK = {
        0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, (byte) 0x8b, 0x67, 0x01
    };
    private static final long MAX_INDEX_OFFSET = 64L * 1024 * 1024;
    private static final int MAX_INDEX_SIZE = 16 * 1024 * 1024;

    private Xp3ArchiveProbe() {
    }

    static boolean containsRootStartup(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                return false;
            }
            DataInputStream input = new DataInputStream(new BufferedInputStream(raw));
            byte[] mark = input.readNBytes(XP3_MARK.length);
            if (!Arrays.equals(mark, XP3_MARK)) {
                return false;
            }
            long indexOffset = readU64(input);
            if (indexOffset < 19 || indexOffset > MAX_INDEX_OFFSET) {
                return false;
            }
            skipFully(input, indexOffset - 19);
            int flags = input.readUnsignedByte();
            if ((flags & 0x07) != 0) {
                return false;
            }
            long indexSize = readU64(input);
            if (indexSize < 0 || indexSize > MAX_INDEX_SIZE) {
                return false;
            }
            byte[] index = input.readNBytes((int) indexSize);
            if (index.length != (int) indexSize) {
                return false;
            }
            return containsStartupInfo(index);
        } catch (EOFException | SecurityException malformedOrRevoked) {
            return false;
        }
    }

    private static boolean containsStartupInfo(byte[] index) {
        int position = 0;
        while (position <= index.length - 12) {
            String name = new String(index, position, 4, StandardCharsets.US_ASCII);
            long size = readU64(index, position + 4);
            if (size < 0 || size > index.length - position - 12) {
                return false;
            }
            if ("File".equals(name)
                && fileChunkHasStartup(index, position + 12, (int) size)) {
                return true;
            }
            position += 12 + (int) size;
        }
        return false;
    }

    private static boolean fileChunkHasStartup(byte[] data, int start, int size) {
        int position = start;
        int end = start + size;
        while (position <= end - 12) {
            String name = new String(data, position, 4, StandardCharsets.US_ASCII);
            long chunkSize = readU64(data, position + 4);
            if (chunkSize < 0 || chunkSize > end - position - 12) {
                return false;
            }
            if ("info".equals(name) && chunkSize >= 22) {
                int body = position + 12;
                long flags = readU32(data, body);
                int length = readU16(data, body + 20);
                if ((flags & 0x80000000L) == 0
                    && length == "startup.tjs".length()
                    && 22 + length * 2 <= chunkSize
                    && equalsAsciiUtf16(data, body + 22, "startup.tjs")) {
                    return true;
                }
            }
            position += 12 + (int) chunkSize;
        }
        return false;
    }

    private static boolean equalsAsciiUtf16(byte[] data, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            int character = readU16(data, offset + index * 2);
            if (Character.toLowerCase(character) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped == 0) {
                if (input.read() == -1) {
                    throw new EOFException();
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static int readU16(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset])
            | (Byte.toUnsignedInt(data[offset + 1]) << 8);
    }

    private static long readU32(byte[] data, int offset) {
        return Integer.toUnsignedLong(
            Byte.toUnsignedInt(data[offset])
                | (Byte.toUnsignedInt(data[offset + 1]) << 8)
                | (Byte.toUnsignedInt(data[offset + 2]) << 16)
                | (Byte.toUnsignedInt(data[offset + 3]) << 24)
        );
    }

    private static long readU64(byte[] data, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value |= (long) Byte.toUnsignedInt(data[offset + index]) << (index * 8);
        }
        return value;
    }

    private static long readU64(DataInputStream input) throws IOException {
        byte[] data = input.readNBytes(8);
        if (data.length != 8) {
            throw new EOFException();
        }
        return readU64(data, 0);
    }
}
