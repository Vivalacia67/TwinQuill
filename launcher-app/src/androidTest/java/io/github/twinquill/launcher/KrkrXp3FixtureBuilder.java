/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Adler32;
import java.util.zip.Deflater;

final class KrkrXp3FixtureBuilder {
    private static final byte[] XP3_MARK = {
        0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, (byte) 0x8b, 0x67, 0x01
    };
    private static final byte INDEX_METHOD_ZLIB = 1;
    private static final int SEGMENT_METHOD_ZLIB = 1;
    private static final String STARTUP_NAME = "startup.tjs";
    private static final byte[] STARTUP_SOURCE =
        "global.twinQuillM0Result = 42;".getBytes(StandardCharsets.US_ASCII);

    private KrkrXp3FixtureBuilder() {
    }

    static byte[] compressedStartupArchive() throws IOException {
        return archive(false, false);
    }

    static byte[] archiveWithContinuedCompressedIndex() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        long segmentOffset = XP3_MARK.length + 8L;
        byte[] emptyIndex = new byte[0];
        long firstIndexOffset = segmentOffset + segment.length;
        byte[] firstIndexBlock = indexBlock(emptyIndex, true, 0);
        long secondIndexOffset = firstIndexOffset + firstIndexBlock.length;
        firstIndexBlock = indexBlock(emptyIndex, true, secondIndexOffset);
        byte[] secondIndexBlock = indexBlock(index(segmentOffset, segment.length), false, 0);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, firstIndexOffset);
        archive.write(segment);
        archive.write(firstIndexBlock);
        archive.write(secondIndexBlock);
        return archive.toByteArray();
    }

    static byte[] archiveWithSelfLoopingIndexContinuation() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        long segmentOffset = XP3_MARK.length + 8L;
        long indexOffset = segmentOffset + segment.length;

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(segment);
        archive.write(indexBlock(new byte[0], true, indexOffset));
        return archive.toByteArray();
    }

    static byte[] archiveWithCorruptCompressedIndex() throws IOException {
        return archive(true, false);
    }

    static byte[] archiveWithCorruptCompressedSegment() throws IOException {
        return archive(false, true);
    }

    private static byte[] archive(
        boolean corruptCompressedIndex,
        boolean corruptCompressedSegment
    ) throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        if (corruptCompressedSegment) {
            corruptPayload(segment);
        }

        long segmentOffset = XP3_MARK.length + 8L;
        byte[] index = index(segmentOffset, segment.length);
        byte[] compressedIndex = deflate(index);
        if (corruptCompressedIndex) {
            corruptPayload(compressedIndex);
        }

        long indexOffset = segmentOffset + segment.length;
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(segment);
        writeIndexBlock(archive, index, compressedIndex, false, 0);
        return archive.toByteArray();
    }

    private static byte[] index(long segmentOffset, int archivedSize)
        throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeChunk(file, "info", info(archivedSize));
        writeChunk(file, "segm", segment(segmentOffset, archivedSize));
        writeChunk(file, "adlr", adler32());

        ByteArrayOutputStream index = new ByteArrayOutputStream();
        writeChunk(index, "File", file.toByteArray());
        return index.toByteArray();
    }

    private static byte[] info(int archivedSize) throws IOException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        writeU32(info, 0);
        writeU64(info, STARTUP_SOURCE.length);
        writeU64(info, archivedSize);
        writeU16(info, STARTUP_NAME.length());
        for (int index = 0; index < STARTUP_NAME.length(); index++) {
            writeU16(info, STARTUP_NAME.charAt(index));
        }
        return info.toByteArray();
    }

    private static byte[] segment(long offset, int archivedSize)
        throws IOException {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        writeU32(segment, SEGMENT_METHOD_ZLIB);
        writeU64(segment, offset);
        writeU64(segment, STARTUP_SOURCE.length);
        writeU64(segment, archivedSize);
        return segment.toByteArray();
    }

    private static byte[] adler32() throws IOException {
        Adler32 checksum = new Adler32();
        checksum.update(STARTUP_SOURCE);

        ByteArrayOutputStream adler = new ByteArrayOutputStream();
        writeU32(adler, (int) checksum.getValue());
        return adler.toByteArray();
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] buffer = new byte[256];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            output.write(buffer, 0, count);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static void corruptPayload(byte[] payload) {
        payload[payload.length / 2] ^= (byte) 0x40;
    }

    private static byte[] indexBlock(byte[] index, boolean continued, long nextOffset)
        throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeIndexBlock(block, index, deflate(index), continued, nextOffset);
        return block.toByteArray();
    }

    private static void writeIndexBlock(
        ByteArrayOutputStream output,
        byte[] index,
        byte[] compressedIndex,
        boolean continued,
        long nextOffset
    ) {
        output.write(INDEX_METHOD_ZLIB | (continued ? 0x80 : 0));
        writeU64(output, compressedIndex.length);
        writeU64(output, index.length);
        output.write(compressedIndex, 0, compressedIndex.length);
        if (continued) {
            writeU64(output, nextOffset);
        }
    }

    private static void writeChunk(
        ByteArrayOutputStream output,
        String name,
        byte[] payload
    ) throws IOException {
        output.write(name.getBytes(StandardCharsets.US_ASCII));
        writeU64(output, payload.length);
        output.write(payload);
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream output, int value) {
        for (int shift = 0; shift < 32; shift += 8) {
            output.write((value >>> shift) & 0xff);
        }
    }

    private static void writeU64(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            output.write((int) ((value >>> shift) & 0xff));
        }
    }
}
