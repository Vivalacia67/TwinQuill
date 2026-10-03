/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.twinquill.launcher.KrkrM3FixtureDocumentsProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public final class Xp3ArchiveProbeInstrumentedTest {
    @Test public void matchesSharedNativeArchiveMatrix() throws Exception {
        Context fixtures = InstrumentationRegistry.getInstrumentation().getContext();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONArray vectors;
        try (InputStream input = fixtures.getAssets().open("m3-vectors/vectors.json")) {
            vectors = new JSONArray(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        File file = File.createTempFile("m3-probe-", ".xp3", target.getCacheDir());
        try {
            for (int i = 0; i < vectors.length(); ++i) {
                JSONObject vector = vectors.getJSONObject(i);
                try (InputStream input = fixtures.getAssets().open("m3-vectors/" + vector.getString("file"))) {
                    Files.write(file.toPath(), input.readAllBytes());
                }
                try (FileInputStream input = new FileInputStream(file)) {
                    assertEquals(vector.getString("file"), vector.getBoolean("detect"),
                        Xp3ArchiveProbe.containsRootStartup(input.getChannel()));
                }
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void detectsCompressedStartupThroughPipeProviderAndCleansSpool() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle grant = target.getContentResolver().call("io.github.twinquill.test.grants", "grant", "m3-compressed", null);
        Uri tree = grant.getParcelable("uri");
        target.getContentResolver().takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri archive = DocumentsContract.buildDocumentUriUsingTree(tree, "m3-compressed/data.xp3");
        try {
            assertTrue(Xp3ArchiveProbe.containsRootStartup(target.getContentResolver(), archive));
            SafGameDirectoryProbe probe = new SafGameDirectoryProbe(target.getContentResolver(), tree);
            assertTrue(probe.archiveContainsRootStartup("data.xp3"));
            File temporary = new File(System.getProperty("java.io.tmpdir"));
            File[] leaks = temporary.listFiles((directory, name) -> name.startsWith("tq-xp3-probe-"));
            assertTrue(leaks != null && leaks.length == 0);
        } finally {
            target.getContentResolver().releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            target.getContentResolver().call("io.github.twinquill.test.grants", "revoke", "m3-compressed", null);
        }
    }
}
