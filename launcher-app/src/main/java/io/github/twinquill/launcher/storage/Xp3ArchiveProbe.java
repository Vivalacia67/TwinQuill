/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded probe for XP3 indexes used as Kirikiri detector evidence. */
final class Xp3ArchiveProbe {
    private static final byte[] XP3_MARK = {
        0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, (byte) 0x8b, 0x67, 0x01
    };
    private static final int XP3_HEADER_SIZE = XP3_MARK.length + 8;
    private static final int INDEX_BASE_HEADER_SIZE = 9;
    private static final int INDEX_COMPRESSED_HEADER_SIZE = 17;
    private static final int MAX_INDEX_SIZE = 16 * 1024 * 1024;
    private static final long MAX_COMPRESSED_INDEX_SIZE = 64L * 1024 * 1024;
    private static final int MAX_INDEX_BLOCKS = 64;
    private static final long MAX_STREAM_FALLBACK_READ = 256L * 1024 * 1024;
    private static final int XP3_METHOD_MASK = 0x07;
    private static final int XP3_CONTINUATION = 0x80;
    private static final int XP3_INDEX_SUPPORTED_FLAGS = XP3_CONTINUATION | XP3_METHOD_MASK;
    private static final long PROTECTED_FILE = 1L << 31;
    private static final long UNKNOWN_SIZE = -1L;

    private Xp3ArchiveProbe() {
    }

    static boolean containsRootStartup(ContentResolver resolver, Uri uri) throws IOException {
        try {
            Boolean seekable = containsRootStartupSeekable(resolver, uri);
            if (seekable != null) {
                return seekable;
            }
            return containsRootStartupSequential(resolver, uri);
        } catch (EOFException | SecurityException malformedOrRevoked) {
            return false;
        }
    }

    private static Boolean containsRootStartupSeekable(ContentResolver resolver, Uri uri)
        throws IOException {
        ParcelFileDescriptor descriptor;
        try {
            descriptor = resolver.openFileDescriptor(uri, "r");
        } catch (FileNotFoundException unsupported) {
            return null;
        }
        if (descriptor == null) {
            return null;
        }
        try (ParcelFileDescriptor closeableDescriptor = descriptor;
             FileInputStream stream = new FileInputStream(closeableDescriptor.getFileDescriptor())) {
            FileChannel channel = stream.getChannel();
            if (!isSeekable(closeableDescriptor)) {
                return null;
            }
            long size = closeableDescriptor.getStatSize();
            if (size < 0) {
                try {
                    size = channel.size();
                } catch (IOException unknownOrUnseekable) {
                    size = UNKNOWN_SIZE;
                }
            }
            return containsRootStartup(new ChannelArchiveReader(channel, size));
        } catch (IOException | UnsupportedOperationException unseekableProvider) {
            return null;
        }
    }

    private static boolean isSeekable(ParcelFileDescriptor descriptor) {
        try {
            Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException unsupported) {
            return false;
        }
    }

    private static boolean containsRootStartupSequential(ContentResolver resolver, Uri uri)
        throws IOException {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                return false;
            }
            return containsRootStartup(new SequentialArchiveReader(raw, MAX_STREAM_FALLBACK_READ));
        }
    }

    private static boolean containsRootStartup(ArchiveReader reader) throws IOException {
        byte[] header = readPayload(reader, 0, XP3_HEADER_SIZE, XP3_HEADER_SIZE);
        if (header == null || !startsWithXp3Mark(header)) {
            return false;
        }
        long indexOffset = readU64(header, XP3_MARK.length);
        if (indexOffset < XP3_HEADER_SIZE) {
            return false;
        }

        Set<Long> visited = new HashSet<>();
        for (int block = 0; block < MAX_INDEX_BLOCKS; block++) {
            if (!visited.add(indexOffset) || !rangeReadable(reader, indexOffset, INDEX_BASE_HEADER_SIZE)) {
                return false;
            }
            IndexPayload payload = readIndexPayload(reader, indexOffset);
            if (payload == null) {
                return false;
            }
            if (containsStartupInfo(payload.index)) {
                return true;
            }
            if ((payload.flags & XP3_CONTINUATION) == 0) {
                return false;
            }

            byte[] nextOffsetData = readPayload(reader, payload.nextOffsetPosition, 8, 8);
            if (nextOffsetData == null) {
                return false;
            }
            long nextOffset = readU64(nextOffsetData, 0);
            if (nextOffset < XP3_HEADER_SIZE || visited.contains(nextOffset)) {
                return false;
            }
            indexOffset = nextOffset;
        }
        return false;
    }

    private static IndexPayload readIndexPayload(ArchiveReader reader, long indexOffset)
        throws IOException {
        byte[] header = readPayload(reader, indexOffset, INDEX_BASE_HEADER_SIZE, INDEX_BASE_HEADER_SIZE);
        if (header == null) {
            return null;
        }
        int flags = Byte.toUnsignedInt(header[0]);
        if ((flags & ~XP3_INDEX_SUPPORTED_FLAGS) != 0) {
            return null;
        }
        int method = flags & XP3_METHOD_MASK;
        if (method == 0) {
            long rawSize = readU64(header, 1);
            byte[] index = readPayload(
                reader,
                checkedAdd(indexOffset, INDEX_BASE_HEADER_SIZE),
                rawSize,
                MAX_INDEX_SIZE
            );
            if (index == null) {
                return null;
            }
            return new IndexPayload(
                flags,
                index,
                checkedAdd(indexOffset, checkedAdd(INDEX_BASE_HEADER_SIZE, rawSize))
            );
        }
        if (method != 1) {
            return null;
        }

        byte[] uncompressedSizeData = readPayload(
            reader,
            checkedAdd(indexOffset, INDEX_BASE_HEADER_SIZE),
            8,
            8
        );
        if (uncompressedSizeData == null) {
            return null;
        }
        long compressedSize = readU64(header, 1);
        long uncompressedSize = readU64(uncompressedSizeData, 0);
        byte[] compressed = readPayload(
            reader,
            checkedAdd(indexOffset, INDEX_COMPRESSED_HEADER_SIZE),
            compressedSize,
            MAX_COMPRESSED_INDEX_SIZE
        );
        if (compressed == null) {
            return null;
        }
        byte[] index = inflateExact(compressed, uncompressedSize, MAX_INDEX_SIZE);
        if (index == null) {
            return null;
        }
        return new IndexPayload(
            flags,
            index,
            checkedAdd(indexOffset, checkedAdd(INDEX_COMPRESSED_HEADER_SIZE, compressedSize))
        );
    }

    private static boolean containsStartupInfo(byte[] index) {
        int position = 0;
        while (position < index.length) {
            if (index.length - position < 12) {
                return false;
            }
            String name = new String(index, position, 4, StandardCharsets.US_ASCII);
            long size = readU64(index, position + 4);
            if (size < 0 || size > index.length - position - 12) {
                return false;
            }
            if ("File".equals(name)) {
                StartupScanResult result = fileChunkHasStartup(index, position + 12, (int) size);
                if (result == StartupScanResult.FOUND) {
                    return true;
                }
                if (result == StartupScanResult.MALFORMED) {
                    return false;
                }
            }
            position += 12 + (int) size;
        }
        return false;
    }

    private static StartupScanResult fileChunkHasStartup(byte[] data, int start, int size) {
        int position = start;
        int end = start + size;
        while (position < end) {
            if (end - position < 12) {
                return StartupScanResult.MALFORMED;
            }
            String name = new String(data, position, 4, StandardCharsets.US_ASCII);
            long chunkSize = readU64(data, position + 4);
            if (chunkSize < 0 || chunkSize > end - position - 12) {
                return StartupScanResult.MALFORMED;
            }
            if ("info".equals(name)) {
                if (chunkSize < 22) {
                    return StartupScanResult.MALFORMED;
                }
                int body = position + 12;
                long flags = readU32(data, body);
                if ((flags & ~PROTECTED_FILE) != 0) {
                    return StartupScanResult.MALFORMED;
                }
                int length = readU16(data, body + 20);
                long nameBytes = (long) length * 2;
                if (22 + nameBytes > chunkSize) {
                    return StartupScanResult.MALFORMED;
                }
                if (length == "startup.tjs".length()
                    && equalsAsciiUtf16(data, body + 22, "startup.tjs")) {
                    return StartupScanResult.FOUND;
                }
            }
            position += 12 + (int) chunkSize;
        }
        return StartupScanResult.NOT_FOUND;
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

    private static boolean startsWithXp3Mark(byte[] data) {
        if (data.length < XP3_MARK.length) {
            return false;
        }
        for (int index = 0; index < XP3_MARK.length; index++) {
            if (data[index] != XP3_MARK[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readPayload(
        ArchiveReader reader,
        long offset,
        long size,
        long maximumSize
    ) throws IOException {
        if (size < 0 || size > maximumSize || size > Integer.MAX_VALUE
            || !rangeReadable(reader, offset, size)) {
            return null;
        }
        byte[] data = new byte[(int) size];
        return reader.readFully(offset, data) ? data : null;
    }

    private static boolean rangeReadable(ArchiveReader reader, long offset, long size) {
        long end = checkedAdd(offset, size);
        if (offset < 0 || size < 0 || end < 0) {
            return false;
        }
        long archiveSize = reader.size();
        return archiveSize < 0 || end <= archiveSize;
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
            return -1;
        }
        return left + right;
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

    private static byte[] inflateExact(
        byte[] compressed,
        long expectedSize,
        long maximumSize
    ) {
        if (expectedSize < 0 || expectedSize > maximumSize || expectedSize > Integer.MAX_VALUE) {
            return null;
        }
        byte[] output = new byte[(int) expectedSize];
        byte[] emptyOutputScratch = new byte[1];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int position = 0;
            while (!inflater.finished()) {
                byte[] target = output.length == 0 ? emptyOutputScratch : output;
                int offset = output.length == 0 ? 0 : position;
                int length = output.length == 0 ? emptyOutputScratch.length : output.length - position;
                if (length <= 0) {
                    return null;
                }
                int inflated = inflater.inflate(target, offset, length);
                if (output.length == 0 && inflated != 0) {
                    return null;
                }
                position += output.length == 0 ? 0 : inflated;
                if (inflated == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        return null;
                    }
                    return null;
                }
            }
            return position == output.length && inflater.getRemaining() == 0 ? output : null;
        } catch (DataFormatException malformed) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private interface ArchiveReader {
        boolean readFully(long offset, byte[] data) throws IOException;

        long size();
    }

    private static final class ChannelArchiveReader implements ArchiveReader {
        private final FileChannel channel;
        private final long size;

        ChannelArchiveReader(FileChannel channel, long size) {
            this.channel = channel;
            this.size = size;
        }

        @Override
        public boolean readFully(long offset, byte[] data) throws IOException {
            if (!rangeReadable(this, offset, data.length)) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.wrap(data);
            channel.position(offset);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read <= 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public long size() {
            return size;
        }
    }

    private static final class SequentialArchiveReader implements ArchiveReader {
        private final InputStream input;
        private final long maximumRead;
        private long position;

        SequentialArchiveReader(InputStream input, long maximumRead) {
            this.input = input;
            this.maximumRead = maximumRead;
        }

        @Override
        public boolean readFully(long offset, byte[] data) throws IOException {
            long end = checkedAdd(offset, data.length);
            if (offset < position || end < 0 || end > maximumRead) {
                return false;
            }
            skipFully(offset - position);
            int read = 0;
            while (read < data.length) {
                int count = input.read(data, read, data.length - read);
                if (count < 0) {
                    return false;
                }
                if (count == 0) {
                    int single = input.read();
                    if (single < 0) {
                        return false;
                    }
                    data[read++] = (byte) single;
                    position++;
                } else {
                    read += count;
                    position += count;
                }
            }
            return true;
        }

        @Override
        public long size() {
            return UNKNOWN_SIZE;
        }

        private void skipFully(long count) throws IOException {
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
                position += skipped;
            }
        }
    }

    private enum StartupScanResult {
        FOUND,
        NOT_FOUND,
        MALFORMED
    }

    private static final class IndexPayload {
        final int flags;
        final byte[] index;
        final long nextOffsetPosition;

        IndexPayload(int flags, byte[] index, long nextOffsetPosition) {
            this.flags = flags;
            this.index = index;
            this.nextOffsetPosition = nextOffsetPosition;
        }
    }
}
