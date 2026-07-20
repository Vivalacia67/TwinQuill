/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.nativevfs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.io.FileInputStream;

@RunWith(AndroidJUnit4.class)
public final class NativeVfsInstrumentedTest {
    private static final int INVALID = -5;
    private static final int PERMISSION = -2;
    private static final int NOT_FOUND = -3;
    private static final int UNSUPPORTED = -4;

    private Uri treeUri;

    @Before
    public void installVfs() {
        Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
        NativeVfs.install(context);
        treeUri = DocumentsContract.buildTreeDocumentUri(
            FixtureDocumentsProvider.AUTHORITY,
            FixtureDocumentsProvider.ROOT_ID
        );
    }

    @Test
    public void listsUnicodeAndSeeksRegularFile() {
        String[] names = NativeVfs.list(treeUri, "");
        assertNotNull(names);
        assertTrue(Arrays.asList(names).contains("中文_日本語.txt"));

        long[] stat = NativeVfs.stat(treeUri, "中文_日本語.txt");
        assertNotNull(stat);
        assertArrayEquals(new long[] {0, 1, 0, 16, 0, 0}, stat);

        long handle = NativeVfs.open(treeUri, "中文_日本語.txt");
        assertTrue(handle > 0);
        try {
            assertEquals(10, NativeVfs.seek(handle, 10, NativeVfs.SEEK_SET));
            byte[] output = new byte[6];
            assertEquals(6, NativeVfs.read(handle, output, 0, output.length));
            assertEquals("abcdef", new String(output, StandardCharsets.UTF_8));
            assertEquals(0, NativeVfs.read(handle, output, 0, output.length));
        } finally {
            assertEquals(0, NativeVfs.close(handle));
        }
    }

    @Test
    public void cachesNonSeekableProviderFile() {
        long handle = NativeVfs.open(treeUri, "cloud.bin");
        assertTrue(handle > 0);
        try {
            int offset = 900_000;
            assertEquals(offset, NativeVfs.seek(handle, offset, NativeVfs.SEEK_SET));
            byte[] output = new byte[8];
            assertEquals(output.length, NativeVfs.read(handle, output, 0, output.length));
            for (int index = 0; index < output.length; index++) {
                assertEquals(
                    FixtureDocumentsProvider.cloudByte(offset + index),
                    output[index]
                );
            }
        } finally {
            assertEquals(0, NativeVfs.close(handle));
        }
    }

    @Test
    public void detachesSeekableDescriptorsForLegacyNativeReaders() throws Exception {
        assertDetachedDescriptor("中文_日本語.txt", 10, "abcdef");
        assertDetachedDescriptor("cloud.bin", 900_000, null);
    }

    @Test
    public void resolvesReadPathsLikeCaseInsensitiveGameFilesystems() throws Exception {
        assertDetachedDescriptor("BG/B27A.PNG", 0, "case-image");

        long[] stat = NativeVfs.stat(treeUri, "BG/B27A.PNG");
        assertNotNull(stat);
        assertArrayEquals(new long[] {0, 1, 0, 10, 0, 0}, stat);

        String[] names = NativeVfs.list(treeUri, "BG");
        assertNotNull(names);
        assertArrayEquals(new String[] {"b27a.png"}, names);
    }

    @Test
    public void prefersExactCaseAndRejectsAmbiguousFoldedReads() throws Exception {
        assertDetachedDescriptor("choice.png", 0, "lower");
        assertDetachedDescriptor("CHOICE.PNG", 0, "upper");
        assertEquals(
            NOT_FOUND,
            NativeVfs.openReadOnlyDescriptor(treeUri, "Choice.png")
        );
    }

    @Test
    public void keepsMutationResolutionCaseSensitive() {
        assertEquals(
            NOT_FOUND,
            NativeVfs.rename(treeUri, "Choice.png", "renamed.png")
        );
        assertEquals(NOT_FOUND, NativeVfs.mkdir(treeUri, "BG/new-directory"));
    }

    @Test
    public void uses64BitOffsetsBeyondFourGigabytes() {
        long[] stat = NativeVfs.stat(treeUri, "large.bin");
        assertNotNull(stat);
        assertEquals(0, stat[0]);
        assertEquals(FixtureDocumentsProvider.LARGE_SIZE, stat[3]);

        long handle = NativeVfs.open(treeUri, "large.bin");
        assertTrue(handle > 0);
        try {
            assertEquals(
                FixtureDocumentsProvider.LARGE_SIZE - 1,
                NativeVfs.seek(handle, -1, NativeVfs.SEEK_END)
            );
            byte[] output = new byte[1];
            assertEquals(1, NativeVfs.read(handle, output, 0, 1));
            assertEquals((byte) 0x5a, output[0]);
        } finally {
            assertEquals(0, NativeVfs.close(handle));
        }
    }

    @Test
    public void rejectsTraversalAndReportsPermissionLoss() {
        assertEquals(INVALID, NativeVfs.open(treeUri, "../startup.tjs"));
        assertEquals(PERMISSION, NativeVfs.open(treeUri, "revoked.bin"));
    }

    @Test
    public void reportsReadOnlyMutationsAsUnsupported() {
        assertEquals(UNSUPPORTED, NativeVfs.mkdir(treeUri, "new-directory"));
        assertEquals(
            UNSUPPORTED,
            NativeVfs.rename(treeUri, "中文_日本語.txt", "renamed.txt")
        );
        assertEquals(UNSUPPORTED, NativeVfs.delete(treeUri, "中文_日本語.txt"));
    }

    private void assertDetachedDescriptor(
        String relativePath,
        long offset,
        String expectedText
    ) throws Exception {
        int descriptor = NativeVfs.openReadOnlyDescriptor(treeUri, relativePath);
        assertTrue(descriptor >= 0);
        try (FileInputStream input =
            new ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.adoptFd(descriptor)
            )) {
            input.getChannel().position(offset);
            byte[] output = new byte[expectedText == null ? 8 : expectedText.length()];
            assertEquals(output.length, input.read(output));
            if (expectedText == null) {
                for (int index = 0; index < output.length; index++) {
                    assertEquals(
                        FixtureDocumentsProvider.cloudByte((int) offset + index),
                        output[index]
                    );
                }
            } else {
                assertEquals(expectedText, new String(output, StandardCharsets.UTF_8));
            }
        }
    }
}
