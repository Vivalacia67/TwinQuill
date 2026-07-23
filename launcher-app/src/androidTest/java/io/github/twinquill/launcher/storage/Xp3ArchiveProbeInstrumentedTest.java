/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.launcher.KrkrXp3FixtureBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public final class Xp3ArchiveProbeInstrumentedTest {
    private static final long LARGE_INDEX_OFFSET = 70L * 1024L * 1024L;

    private ContentResolver resolver;
    private File root;

    @Before
    public void createRoot() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver();
        root = new File(
            context.getCacheDir(),
            "xp3-probe-fixtures/" + android.os.SystemClock.elapsedRealtime()
        );
        deleteTree(root);
        assertTrue(root.mkdirs());
    }

    @After
    public void deleteRoot() throws Exception {
        if (root != null) {
            deleteTree(root);
        }
    }

    @Test
    public void detectsStartupInCompressedIndex() throws Exception {
        assertContainsRootStartup(
            KrkrXp3FixtureBuilder.compressedStartupArchive(),
            true
        );
    }

    @Test
    public void treatsProtectedStartupInfoAsDetectionEvidence() throws Exception {
        assertContainsRootStartup(
            KrkrXp3FixtureBuilder.protectedStartupArchive(),
            true
        );
    }

    @Test
    public void followsCompressedIndexContinuationToStartupInfo() throws Exception {
        assertContainsRootStartup(
            KrkrXp3FixtureBuilder.archiveWithContinuedCompressedIndex(),
            true
        );
    }

    @Test
    public void detectsStartupPastFormerSixtyFourMibIndexOffsetCap()
        throws Exception {
        File archive = new File(root, "large-offset.xp3");
        KrkrXp3FixtureBuilder.writeSparseArchiveWithLargeCompressedIndexOffset(
            archive,
            LARGE_INDEX_OFFSET
        );

        assertTrue(
            Xp3ArchiveProbe.containsRootStartup(resolver, Uri.fromFile(archive))
        );
        assertTrue(archive.length() > LARGE_INDEX_OFFSET);
    }

    @Test
    public void rejectsCompressedIndexWithTrailingDeflateBytes() throws Exception {
        assertContainsRootStartup(
            KrkrXp3FixtureBuilder.archiveWithTrailingCompressedIndex(),
            false
        );
    }

    private void assertContainsRootStartup(byte[] archive, boolean expected)
        throws Exception {
        File file = new File(root, "data-" + System.nanoTime() + ".xp3");
        Files.write(file.toPath(), archive);
        assertEquals(
            expected,
            Xp3ArchiveProbe.containsRootStartup(resolver, Uri.fromFile(file))
        );
    }

    private static void deleteTree(File file) throws Exception {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        Files.delete(file.toPath());
    }
}
