/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.Deflater;

public final class KrkrXp3FixtureBuilder {
    private static final byte[] XP3_MARK = {
        0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, (byte) 0x8b, 0x67, 0x01
    };
    private static final int INDEX_METHOD_RAW = 0;
    private static final int INDEX_METHOD_ZLIB = 1;
    private static final int INDEX_CONTINUED = 0x80;
    private static final int SEGMENT_METHOD_RAW = 0;
    private static final int SEGMENT_METHOD_ZLIB = 1;
    private static final long PROTECTED_FILE = 1L << 31;
    private static final String STARTUP_NAME = "startup.tjs";
    private static final String NON_STARTUP_NAME = "readme.tjs";
    private static final byte[] STARTUP_SOURCE =
        "global.twinQuillM0Result = 42;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NON_STARTUP_SOURCE =
        "global.twinQuillUnused = 7;".getBytes(StandardCharsets.US_ASCII);
    private static final String VALID_KAG_SCENARIO_TEXT =
        "*start\n"
            + "plain text\n"
            + "[if exp=\"1\"]\n"
            + "[wait time=\"1\" canskip=true]\n"
            + "[else]\n"
            + "[wait time=\"999\"]\n"
            + "[endif]\n"
            + "[jump target=*done]\n"
            + "*done\n";
    private static final byte[] VALID_KAG_SCENARIO_SOURCE =
        VALID_KAG_SCENARIO_TEXT.getBytes(StandardCharsets.UTF_8);

    private KrkrXp3FixtureBuilder() {
    }

    public static byte[] compressedStartupArchive() throws IOException {
        return archive(
            block(INDEX_METHOD_ZLIB, entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment()))
        );
    }

    public static byte[] archiveWithKagRuntimeStartup(String startupSource)
        throws IOException {
        byte[] source = startupSource.getBytes(StandardCharsets.US_ASCII);
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, source, 0, zlibSegment(source))
            )
        );
    }

    public static byte[] noStartupArchive() throws IOException {
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(NON_STARTUP_NAME, NON_STARTUP_SOURCE, 0, zlibSegment())
            )
        );
    }

    public static byte[] protectedStartupArchive() throws IOException {
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, STARTUP_SOURCE, PROTECTED_FILE, zlibSegment())
            )
        );
    }

    public static byte[] archiveWithStartupAsSecondEntry() throws IOException {
        return archive(
            block(
                INDEX_METHOD_RAW,
                entry(NON_STARTUP_NAME, NON_STARTUP_SOURCE, 0, zlibSegment()),
                entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
            )
        );
    }

    public static byte[] archiveWithKagScenario(
        String scenarioReference,
        String scenarioEntryName
    ) throws IOException {
        return archiveWithKagScenario(
            scenarioReference,
            scenarioEntryName,
            VALID_KAG_SCENARIO_SOURCE
        );
    }

    public static byte[] archiveWithKagScenario(
        String scenarioReference,
        String scenarioEntryName,
        byte[] scenarioSource
    ) throws IOException {
        byte[] startupSource = startupSourceForScenario(scenarioReference);
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, startupSource, 0, zlibSegment(startupSource)),
                entry(scenarioEntryName, scenarioSource, 0, zlibSegment(scenarioSource))
            )
        );
    }

    public static byte[] archiveWithRegisteredKagParserScenario(
        String scenarioReference,
        String scenarioEntryName
    ) throws IOException {
        return archiveWithRegisteredKagParserScenario(
            scenarioReference,
            scenarioEntryName,
            VALID_KAG_SCENARIO_SOURCE
        );
    }

    public static byte[] archiveWithRegisteredKagParserScenario(
        String scenarioReference,
        String scenarioEntryName,
        byte[] scenarioSource
    ) throws IOException {
        byte[] startupSource =
            startupSourceForRegisteredKagParserScenario(scenarioReference);
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, startupSource, 0, zlibSegment(startupSource)),
                entry(scenarioEntryName, scenarioSource, 0, zlibSegment(scenarioSource))
            )
        );
    }

    public static byte[] archiveWithMissingKagScenario(String scenarioReference)
        throws IOException {
        byte[] startupSource = startupSourceForScenario(scenarioReference);
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, startupSource, 0, zlibSegment(startupSource))
            )
        );
    }

    public static byte[] validKagScenarioSource() {
        return Arrays.copyOf(
            VALID_KAG_SCENARIO_SOURCE,
            VALID_KAG_SCENARIO_SOURCE.length
        );
    }

    public static byte[] utf8BomKagScenarioSource() {
        byte[] source = VALID_KAG_SCENARIO_TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[source.length + 3];
        output[0] = (byte) 0xef;
        output[1] = (byte) 0xbb;
        output[2] = (byte) 0xbf;
        System.arraycopy(source, 0, output, 3, source.length);
        return output;
    }

    public static byte[] utf16LeBomKagScenarioSource() {
        byte[] source = VALID_KAG_SCENARIO_TEXT.getBytes(StandardCharsets.UTF_16LE);
        byte[] output = new byte[source.length + 2];
        output[0] = (byte) 0xff;
        output[1] = (byte) 0xfe;
        System.arraycopy(source, 0, output, 2, source.length);
        return output;
    }

    public static byte[] noLabelKagScenarioSource() {
        return ("[wait time=\"1\" canskip=true]\n" + "plain text\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] malformedQuotedAttributeKagScenarioSource() {
        return ("*start\n" + "[wait time=\"100]\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] invalidControlExpressionKagScenarioSource() {
        return ("*start\n" + "[if exp=\"1 +\"]\n" + "[endif]\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] archiveWithRawAndCompressedStartupSegments()
        throws IOException {
        int split = "global.twinQuill".length();
        byte[] prefix = Arrays.copyOfRange(STARTUP_SOURCE, 0, split);
        byte[] suffix = Arrays.copyOfRange(
            STARTUP_SOURCE,
            split,
            STARTUP_SOURCE.length
        );
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(
                    STARTUP_NAME,
                    STARTUP_SOURCE,
                    0,
                    rawSegment(prefix),
                    zlibSegment(suffix)
                )
            )
        );
    }

    public static byte[] archiveWithStartupInSecondContinuedIndexBlock()
        throws IOException {
        return archive(
            continuedBlock(
                INDEX_METHOD_ZLIB,
                entry(NON_STARTUP_NAME, NON_STARTUP_SOURCE, 0, zlibSegment())
            ),
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
            )
        );
    }

    public static byte[] archiveWithSegmentOffsetBeyondArchive()
        throws IOException {
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(
                    STARTUP_NAME,
                    STARTUP_SOURCE,
                    0,
                    zlibSegment(STARTUP_SOURCE).withOffsetOverride(1_000_000L)
                )
            )
        );
    }

    public static byte[] archiveWithInfoOriginalSizeMismatch()
        throws IOException {
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
                    .withOriginalSizeOverride(STARTUP_SOURCE.length + 1L)
            )
        );
    }

    public static byte[] archiveWithRawSegmentSizeMismatch()
        throws IOException {
        return archive(
            block(
                INDEX_METHOD_ZLIB,
                entry(
                    STARTUP_NAME,
                    STARTUP_SOURCE,
                    0,
                    rawSegment(STARTUP_SOURCE)
                        .withArchivedSizeOverride(STARTUP_SOURCE.length + 1L)
                )
            )
        );
    }

    public static byte[] archiveWithContinuedCompressedIndex() throws IOException {
        return archive(
            continuedBlock(INDEX_METHOD_ZLIB),
            block(
                INDEX_METHOD_ZLIB,
                entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
            )
        );
    }

    public static byte[] archiveWithSelfLoopingIndexContinuation() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        long segmentOffset = XP3_MARK.length + 8L;
        long indexOffset = segmentOffset + segment.length;

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(segment);
        archive.write(indexBlock(new byte[0], INDEX_METHOD_ZLIB, true, indexOffset));
        return archive.toByteArray();
    }

    public static byte[] archiveWithCorruptCompressedIndex() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        long segmentOffset = XP3_MARK.length + 8L;
        byte[] index = index(
            entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
                .prepared(segmentOffset, segment.length)
        );
        byte[] compressed = deflate(index);
        corruptPayload(compressed);
        long indexOffset = segmentOffset + segment.length;
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(segment);
        writeIndexBlock(
            archive,
            index,
            compressed,
            INDEX_METHOD_ZLIB,
            false,
            0
        );
        return archive.toByteArray();
    }

    public static byte[] archiveWithTrailingCompressedIndex() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        long segmentOffset = XP3_MARK.length + 8L;
        byte[] index = index(
            entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
                .prepared(segmentOffset, segment.length)
        );
        byte[] compressed = deflate(index);
        byte[] compressedIndex = Arrays.copyOf(compressed, compressed.length + 1);

        long indexOffset = segmentOffset + segment.length;
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(segment);
        writeIndexBlock(
            archive,
            index,
            compressedIndex,
            INDEX_METHOD_ZLIB,
            false,
            0
        );
        return archive.toByteArray();
    }

    public static void writeSparseArchiveWithLargeCompressedIndexOffset(
        File file,
        long indexOffset
    ) throws IOException {
        byte[] index = index(
            entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
                .prepared(XP3_MARK.length + 8L, 0)
        );
        byte[] block = indexBlock(index, INDEX_METHOD_ZLIB, false, 0);
        try (RandomAccessFile archive = new RandomAccessFile(file, "rw")) {
            archive.setLength(0);
            archive.write(XP3_MARK);
            writeU64(archive, indexOffset);
            archive.seek(indexOffset);
            archive.write(block);
        }
    }

    public static byte[] archiveWithCorruptCompressedSegment() throws IOException {
        byte[] segment = deflate(STARTUP_SOURCE);
        corruptPayload(segment);
        long segmentOffset = XP3_MARK.length + 8L;
        byte[] index = index(
            entry(STARTUP_NAME, STARTUP_SOURCE, 0, zlibSegment())
                .prepared(segmentOffset, segment.length)
        );
        return archiveWithPayloadAndIndex(segment, index, INDEX_METHOD_ZLIB);
    }

    private static byte[] startupSourceForScenario(String scenarioReference) {
        String escaped = scenarioReference.replace("\\", "\\\\").replace("\"", "\\\"");
        return (
            "global.twinQuillM0Result = 42;"
                + "global.twinQuillM3KagProbeScenario = \"" + escaped + "\";"
        ).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] startupSourceForRegisteredKagParserScenario(
        String scenarioReference
    ) {
        String escaped = storageReferenceForScenario(scenarioReference)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        return (
            "var parser = new KAGParser();\n"
                + "var runtime = new TwinQuillKagRuntime();\n"
                + "parser.loadScenario(\"" + escaped + "\");\n"
                + "while (true) {\n"
                + "    var tag = parser.getNextTag();\n"
                + "    if (tag == void) break;\n"
                + "    runtime.consume(tag);\n"
                + "}\n"
                + "runtime.finish();\n"
                + "if (runtime.finished"
                + " && runtime.tagCount > 0"
                + " && runtime.text == \"plain text\""
                + " && runtime.lineBreakCount > 0"
                + " && runtime.waitCount == 1"
                + " && runtime.lastWaitTime == \"1\""
                + " && runtime.lastWaitCanSkip == \"true\""
                + " && runtime.lastTagName != \"\") {\n"
                + "    global.twinQuillM0Result = 42;\n"
                + "}\n"
        ).getBytes(StandardCharsets.US_ASCII);
    }

    private static String storageReferenceForScenario(String scenarioReference) {
        return scenarioReference.contains("://")
            ? scenarioReference
            : "twinquill://./" + scenarioReference;
    }

    private static byte[] archive(IndexBlockSpec... blocks) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        long dataOffset = XP3_MARK.length + 8L;
        List<PreparedIndexBlock> preparedBlocks = new ArrayList<>();
        for (IndexBlockSpec block : blocks) {
            List<PreparedEntry> entries = new ArrayList<>();
            for (EntrySpec entry : block.entries) {
                entries.add(entry.prepared(dataOffset, payload));
            }
            preparedBlocks.add(new PreparedIndexBlock(block.method, block.continued, index(entries)));
        }

        long indexOffset = dataOffset + payload.size();
        long nextIndexOffset = indexOffset;
        long[] blockOffsets = new long[preparedBlocks.size()];
        byte[][] encodedBlocks = new byte[preparedBlocks.size()][];
        for (int index = 0; index < preparedBlocks.size(); index++) {
            PreparedIndexBlock block = preparedBlocks.get(index);
            blockOffsets[index] = nextIndexOffset;
            encodedBlocks[index] = indexBlock(block.index, block.method, block.continued, 0);
            nextIndexOffset += encodedBlocks[index].length;
        }
        for (int index = 0; index < preparedBlocks.size(); index++) {
            PreparedIndexBlock block = preparedBlocks.get(index);
            long nextOffset = index + 1 < preparedBlocks.size() ? blockOffsets[index + 1] : 0;
            encodedBlocks[index] = indexBlock(
                block.index,
                block.method,
                block.continued,
                nextOffset
            );
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        payload.writeTo(archive);
        for (byte[] block : encodedBlocks) {
            archive.write(block);
        }
        return archive.toByteArray();
    }

    private static byte[] archiveWithPayloadAndIndex(
        byte[] payload,
        byte[] index,
        int method
    ) throws IOException {
        long indexOffset = XP3_MARK.length + 8L + payload.length;
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(XP3_MARK);
        writeU64(archive, indexOffset);
        archive.write(payload);
        archive.write(indexBlock(index, method, false, 0));
        return archive.toByteArray();
    }

    private static IndexBlockSpec block(int method, EntrySpec... entries) {
        return new IndexBlockSpec(method, false, entries);
    }

    private static IndexBlockSpec continuedBlock(int method, EntrySpec... entries) {
        return new IndexBlockSpec(method, true, entries);
    }

    private static EntrySpec entry(
        String name,
        byte[] source,
        long infoFlags,
        SegmentSpec... segments
    ) {
        return new EntrySpec(name, source, infoFlags, segments);
    }

    private static SegmentSpec zlibSegment() {
        return zlibSegment(STARTUP_SOURCE);
    }

    private static SegmentSpec zlibSegment(byte[] source) {
        return new SegmentSpec(SEGMENT_METHOD_ZLIB, source);
    }

    private static SegmentSpec rawSegment(byte[] source) {
        return new SegmentSpec(SEGMENT_METHOD_RAW, source);
    }

    private static byte[] index(List<PreparedEntry> entries) throws IOException {
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        for (PreparedEntry entry : entries) {
            writeChunk(index, "File", file(entry));
        }
        return index.toByteArray();
    }

    private static byte[] index(PreparedEntry entry) throws IOException {
        List<PreparedEntry> entries = new ArrayList<>();
        entries.add(entry);
        return index(entries);
    }

    private static byte[] file(PreparedEntry entry) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeChunk(file, "info", info(entry));
        writeChunk(file, "segm", segments(entry.segments));
        writeChunk(file, "adlr", adler32(entry.source));
        return file.toByteArray();
    }

    private static byte[] info(PreparedEntry entry) throws IOException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        writeU32(info, (int) entry.infoFlags);
        writeU64(info, entry.originalSize);
        writeU64(info, entry.archivedSize);
        writeU16(info, entry.name.length());
        for (int index = 0; index < entry.name.length(); index++) {
            writeU16(info, entry.name.charAt(index));
        }
        return info.toByteArray();
    }

    private static byte[] segments(List<PreparedSegment> segments)
        throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (PreparedSegment segment : segments) {
            writeU32(output, segment.method);
            writeU64(output, segment.offset);
            writeU64(output, segment.originalSize);
            writeU64(output, segment.archivedSize);
        }
        return output.toByteArray();
    }

    private static byte[] adler32(byte[] source) throws IOException {
        Adler32 checksum = new Adler32();
        checksum.update(source);

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

    private static byte[] indexBlock(
        byte[] index,
        int method,
        boolean continued,
        long nextOffset
    ) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        if (method == INDEX_METHOD_RAW) {
            writeIndexBlock(block, index, index, method, continued, nextOffset);
        } else {
            writeIndexBlock(block, index, deflate(index), method, continued, nextOffset);
        }
        return block.toByteArray();
    }

    private static void writeIndexBlock(
        ByteArrayOutputStream output,
        byte[] index,
        byte[] encodedIndex,
        int method,
        boolean continued,
        long nextOffset
    ) {
        output.write(method | (continued ? INDEX_CONTINUED : 0));
        writeU64(output, encodedIndex.length);
        if (method == INDEX_METHOD_ZLIB) {
            writeU64(output, index.length);
        }
        output.write(encodedIndex, 0, encodedIndex.length);
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

    private static void writeU64(RandomAccessFile output, long value)
        throws IOException {
        for (int shift = 0; shift < 64; shift += 8) {
            output.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static final class IndexBlockSpec {
        final int method;
        final boolean continued;
        final EntrySpec[] entries;

        IndexBlockSpec(int method, boolean continued, EntrySpec[] entries) {
            this.method = method;
            this.continued = continued;
            this.entries = entries;
        }
    }

    private static final class PreparedIndexBlock {
        final int method;
        final boolean continued;
        final byte[] index;

        PreparedIndexBlock(int method, boolean continued, byte[] index) {
            this.method = method;
            this.continued = continued;
            this.index = index;
        }
    }

    private static final class EntrySpec {
        final String name;
        final byte[] source;
        final long infoFlags;
        final SegmentSpec[] segments;
        Long originalSizeOverride;

        EntrySpec(String name, byte[] source, long infoFlags, SegmentSpec[] segments) {
            this.name = name;
            this.source = source;
            this.infoFlags = infoFlags;
            this.segments = segments;
        }

        EntrySpec withOriginalSizeOverride(long originalSize) {
            originalSizeOverride = originalSize;
            return this;
        }

        PreparedEntry prepared(long firstPayloadOffset, ByteArrayOutputStream payload)
            throws IOException {
            List<PreparedSegment> preparedSegments = new ArrayList<>();
            long originalSize = 0;
            long archivedSize = 0;
            for (SegmentSpec segment : segments) {
                PreparedSegment prepared = segment.prepared(firstPayloadOffset + payload.size());
                preparedSegments.add(prepared);
                originalSize += prepared.originalSize;
                archivedSize += prepared.archivedSize;
                if (!segment.hasOffsetOverride()) {
                    payload.write(prepared.payload);
                }
            }
            return new PreparedEntry(
                name,
                source,
                infoFlags,
                originalSizeOverride == null ? originalSize : originalSizeOverride,
                archivedSize,
                preparedSegments
            );
        }

        PreparedEntry prepared(long payloadOffset, int archivedSize) throws IOException {
            PreparedSegment segment = new PreparedSegment(
                SEGMENT_METHOD_ZLIB,
                payloadOffset,
                source.length,
                archivedSize,
                new byte[0]
            );
            List<PreparedSegment> preparedSegments = new ArrayList<>();
            preparedSegments.add(segment);
            return new PreparedEntry(
                name,
                source,
                infoFlags,
                source.length,
                archivedSize,
                preparedSegments
            );
        }
    }

    private static final class SegmentSpec {
        final int method;
        final byte[] original;
        Long offsetOverride;
        Long archivedSizeOverride;

        SegmentSpec(int method, byte[] original) {
            this.method = method;
            this.original = original;
        }

        SegmentSpec withOffsetOverride(long offset) {
            offsetOverride = offset;
            return this;
        }

        SegmentSpec withArchivedSizeOverride(long archivedSize) {
            archivedSizeOverride = archivedSize;
            return this;
        }

        boolean hasOffsetOverride() {
            return offsetOverride != null;
        }

        PreparedSegment prepared(long payloadOffset) {
            byte[] payload = method == SEGMENT_METHOD_RAW ? original : deflate(original);
            long offset = offsetOverride == null ? payloadOffset : offsetOverride;
            long archivedSize = archivedSizeOverride == null
                ? payload.length
                : archivedSizeOverride;
            return new PreparedSegment(
                method,
                offset,
                original.length,
                archivedSize,
                payload
            );
        }
    }

    private static final class PreparedEntry {
        final String name;
        final byte[] source;
        final long infoFlags;
        final long originalSize;
        final long archivedSize;
        final List<PreparedSegment> segments;

        PreparedEntry(
            String name,
            byte[] source,
            long infoFlags,
            long originalSize,
            long archivedSize,
            List<PreparedSegment> segments
        ) {
            this.name = name;
            this.source = source;
            this.infoFlags = infoFlags;
            this.originalSize = originalSize;
            this.archivedSize = archivedSize;
            this.segments = segments;
        }
    }

    private static final class PreparedSegment {
        final int method;
        final long offset;
        final long originalSize;
        final long archivedSize;
        final byte[] payload;

        PreparedSegment(
            int method,
            long offset,
            long originalSize,
            long archivedSize,
            byte[] payload
        ) {
            this.method = method;
            this.offset = offset;
            this.originalSize = originalSize;
            this.archivedSize = archivedSize;
            this.payload = payload;
        }
    }
}
