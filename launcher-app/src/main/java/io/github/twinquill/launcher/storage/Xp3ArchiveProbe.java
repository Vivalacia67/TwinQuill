/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Metadata-only XP3 detection, using the same format and limits as the M3 native reader. */
final class Xp3ArchiveProbe {
    private static final byte[] MARK = {0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a,
        (byte) 0x8b, 0x67, 0x01};
    private static final long ARCHIVE_LIMIT = 4L * 1024 * 1024 * 1024;
    private static final long SPOOL_LIMIT = 128L * 1024 * 1024;
    private static final int INDEX_LIMIT = 16 * 1024 * 1024;
    private static final int SEGMENT_LIMIT = 32 * 1024 * 1024;

    private Xp3ArchiveProbe() { }

    static boolean containsRootStartup(ContentResolver resolver, Uri uri) throws IOException {
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) return false;
            try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                FileChannel channel = input.getChannel();
                try {
                    channel.position(0);
                    channel.size();
                } catch (IOException pipe) {
                    input.close();
                    return spoolAndProbe(resolver, uri);
                }
                return containsRootStartup(channel);
            }
        } catch (SecurityException revoked) { return false; }
    }

    private static boolean spoolAndProbe(ContentResolver resolver, Uri uri) throws IOException {
        File cache = File.createTempFile("tq-xp3-probe-", ".cache");
        try {
            try (InputStream input = resolver.openInputStream(uri);
                    FileOutputStream output = new FileOutputStream(cache)) {
                if (input == null) return false;
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) {
                        int next = input.read();
                        if (next < 0) break;
                        buffer[0] = (byte) next; count = 1;
                    }
                    total += count;
                    if (total > SPOOL_LIMIT) return false;
                    output.write(buffer, 0, count);
                }
            }
            try (FileInputStream input = new FileInputStream(cache)) {
                return containsRootStartup(input.getChannel());
            }
        } finally {
            if (!cache.delete() && cache.exists()) throw new IOException("Unable to remove XP3 probe cache");
        }
    }

    // Kept package-private for the shared authored fixture matrix on the emulator.
    static boolean containsRootStartup(FileChannel input) throws IOException {
        try {
            long archiveSize = input.size();
            require(archiveSize >= 19 && archiveSize <= ARCHIVE_LIMIT);
            byte[] header = at(input, 0, 19);
            require(Arrays.equals(MARK, Arrays.copyOf(header, 11)));
            long offset = u64(header, 11);
            int indexTotal = 0, segmentsTotal = 0;
            Set<Long> visited = new HashSet<>();
            Set<String> members = new HashSet<>();
            boolean startup = false;
            while (true) {
                require(offset >= 19 && visited.size() < 32 && visited.add(offset));
                byte[] block = at(input, offset, 9);
                int flags = Byte.toUnsignedInt(block[0]);
                require((flags & ~0x81) == 0);
                long packedSize = u64(block, 1);
                long size = (flags & 1) == 0 ? packedSize : u64(at(input, offset + 9, 8), 0);
                require(packedSize >= 0 && packedSize <= INDEX_LIMIT && size >= 0 && size <= INDEX_LIMIT - indexTotal);
                indexTotal += (int) size;
                long dataOffset = offset + ((flags & 1) == 0 ? 9 : 17);
                byte[] bytes = at(input, dataOffset, (int) packedSize);
                if ((flags & 1) != 0) bytes = inflate(bytes, (int) size);
                int cursor = 0;
                while (cursor < bytes.length) {
                    Chunk file = chunk(bytes, cursor, bytes.length);
                    cursor = file.end;
                    if (!"File".equals(file.tag)) continue;
                    require(members.size() < 65536);
                    boolean infoSeen = false, segmSeen = false, adlerSeen = false;
                    String name = null;
                    long original = 0, archived = 0, originalTotal = 0, archivedTotal = 0;
                    long fileFlags = 0;
                    int position = file.start;
                    while (position < file.end) {
                        Chunk part = chunk(bytes, position, file.end);
                        position = part.end;
                        int body = part.start, length = part.end - part.start;
                        if ("info".equals(part.tag)) {
                            require(!infoSeen && length >= 22);
                            infoSeen = true;
                            fileFlags = u32(bytes, body);
                            original = u64(bytes, body + 4); archived = u64(bytes, body + 12);
                            int characters = u16(bytes, body + 20);
                            require(characters > 0 && characters <= 2048 && 22 + 2 * characters <= length);
                            name = normalized(StandardCharsets.UTF_16LE.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes, body + 22, 2 * characters)).toString());
                            require(original >= 0 && original <= 512L * 1024 * 1024 && archived >= 0);
                        } else if ("segm".equals(part.tag)) {
                            require(!segmSeen && length % 28 == 0 && length / 28 <= 4096);
                            segmSeen = true; segmentsTotal += length / 28;
                            require(segmentsTotal <= 262144);
                            for (int i = body; i < part.end; i += 28) {
                                long segmentFlags = u32(bytes, i), start = u64(bytes, i + 4);
                                long unpacked = u64(bytes, i + 12), packed = u64(bytes, i + 20);
                                require(segmentFlags <= 1 && start >= 0 && start <= archiveSize && packed >= 0
                                    && packed <= archiveSize - start && unpacked >= 0);
                                require(segmentFlags != 0 || unpacked == packed);
                                require(segmentFlags == 0 || (unpacked <= SEGMENT_LIMIT && packed <= SEGMENT_LIMIT));
                                require(unpacked <= Long.MAX_VALUE - originalTotal && packed <= Long.MAX_VALUE - archivedTotal);
                                originalTotal += unpacked; archivedTotal += packed;
                            }
                        } else if ("adlr".equals(part.tag)) {
                            require(!adlerSeen && length == 4); adlerSeen = true;
                        }
                    }
                    require(infoSeen && segmSeen && adlerSeen && originalTotal == original && archivedTotal == archived && members.add(name));
                    if ("startup.tjs".equals(name) && (fileFlags & 0x80000000L) == 0 && original > 0 && original <= 8L * 1024 * 1024)
                        startup = true;
                }
                if ((flags & 0x80) == 0) return startup;
                offset = u64(at(input, dataOffset + packedSize, 8), 0);
            }
        } catch (MalformedArchive | DataFormatException | java.nio.charset.CharacterCodingException malformed) { return false; }
    }

    private static byte[] inflate(byte[] packed, int size) throws DataFormatException, MalformedArchive {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            byte[] bytes = new byte[size + 1];
            int total = 0;
            while (!inflater.finished() && total < bytes.length) {
                int count = inflater.inflate(bytes, total, bytes.length - total);
                total += count;
                if (count == 0 && !inflater.finished()) throw new MalformedArchive();
            }
            require(inflater.finished() && total == size && inflater.getBytesRead() == packed.length);
            return Arrays.copyOf(bytes, size);
        } finally { inflater.end(); }
    }
    private static String normalized(String name) throws MalformedArchive {
        require(name.indexOf('\0') < 0 && name.indexOf(':') < 0 && name.indexOf('>') < 0
            && name.getBytes(StandardCharsets.UTF_8).length <= 4096);
        name = name.replace('\\', '/');
        if (name.startsWith("./")) name = name.substring(2);
        require(!name.startsWith("/") && !name.endsWith("/"));
        StringBuilder normalized = new StringBuilder();
        for (String part : name.split("/")) {
            require(!"..".equals(part) && !".".equals(part));
            if (part.isEmpty()) continue;
            if (normalized.length() > 0) normalized.append('/');
            for (int i = 0; i < part.length(); ++i) {
                char c = part.charAt(i);
                normalized.append(c >= 'A' && c <= 'Z' ? (char) (c + 'a' - 'A') : c);
            }
        }
        require(normalized.length() > 0);
        return normalized.toString();
    }
    private static byte[] at(FileChannel input, long offset, int count) throws IOException, MalformedArchive {
        require(offset >= 0 && count >= 0 && offset <= input.size() && count <= input.size() - offset);
        ByteBuffer bytes = ByteBuffer.allocate(count);
        int stalled = 0;
        while (bytes.hasRemaining()) {
            int read = input.read(bytes, offset + bytes.position());
            require(read >= 0 && (read != 0 || ++stalled < 16));
        }
        return bytes.array();
    }
    private static Chunk chunk(byte[] bytes, int cursor, int end) throws MalformedArchive {
        require(cursor <= end && end - cursor >= 12);
        long size = u64(bytes, cursor + 4);
        require(size >= 0 && size <= end - cursor - 12);
        return new Chunk(new String(bytes, cursor, 4, StandardCharsets.US_ASCII), cursor + 12, cursor + 12 + (int) size);
    }
    private static final class Chunk {
        final String tag;
        final int start, end;
        Chunk(String tag, int start, int end) { this.tag = tag; this.start = start; this.end = end; }
    }
    private static final class MalformedArchive extends Exception { }
    private static void require(boolean condition) throws MalformedArchive { if (!condition) throw new MalformedArchive(); }
    private static int u16(byte[] p, int start) { return Byte.toUnsignedInt(p[start]) | (Byte.toUnsignedInt(p[start + 1]) << 8); }
    private static long u32(byte[] p, int start) { return Integer.toUnsignedLong((int) u64Prefix(p, start, 4)); }
    private static long u64(byte[] p, int start) { return u64Prefix(p, start, 8); }
    private static long u64Prefix(byte[] p, int start, int size) {
        long value = 0;
        for (int i = 0; i < size; ++i) value |= (long) Byte.toUnsignedInt(p[start + i]) << (8 * i);
        return value;
    }
}
