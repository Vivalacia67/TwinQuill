/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.krkr.KrkrEngineActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class KrkrCompressedXp3InstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private EngineProtocolTestHostActivity host;

    @Before
    public void startHost() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        host = createHost();
    }

    @After
    public void finishHost() {
        if (host != null) {
            instrumentation.runOnMainSync(host::finish);
        }
    }

    @Test
    public void runsKrkrStartupFromCompressedXp3() throws Exception {
        Fixture fixture = createFixture(
            "krkr-xp3-ok-",
            KrkrXp3FixtureBuilder.compressedStartupArchive()
        );
        try {
            Intent result = launchAndAwait(requestIntent(fixture));
            assertEquals(Activity.RESULT_OK, host.engineResultCode());
            assertEquals(
                EngineResult.NORMAL_EXIT.code(),
                result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(
                0,
                result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
            );
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void runsKrkrStartupFromContinuedCompressedXp3Index() throws Exception {
        Fixture fixture = createFixture(
            "krkr-xp3-continued-index-ok-",
            KrkrXp3FixtureBuilder.archiveWithContinuedCompressedIndex()
        );
        try {
            Intent result = launchAndAwait(requestIntent(fixture));
            assertEquals(Activity.RESULT_OK, host.engineResultCode());
            assertEquals(
                EngineResult.NORMAL_EXIT.code(),
                result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(
                0,
                result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
            );
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void rejectsKrkrXp3WithCorruptCompressedIndex() throws Exception {
        assertScriptError34Result(
            "krkr-xp3-bad-index-",
            KrkrXp3FixtureBuilder.archiveWithCorruptCompressedIndex()
        );
    }

    @Test
    public void rejectsKrkrXp3WithCorruptCompressedSegment() throws Exception {
        assertScriptError34Result(
            "krkr-xp3-bad-segment-",
            KrkrXp3FixtureBuilder.archiveWithCorruptCompressedSegment()
        );
    }

    @Test
    public void rejectsKrkrXp3WithSelfLoopingIndexContinuation() throws Exception {
        assertScriptError34Result(
            "krkr-xp3-loop-index-",
            KrkrXp3FixtureBuilder.archiveWithSelfLoopingIndexContinuation()
        );
    }

    private EngineProtocolTestHostActivity createHost() {
        Intent intent = new Intent(context, EngineProtocolTestHostActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return (EngineProtocolTestHostActivity) instrumentation.startActivitySync(intent);
    }

    private void assertScriptError34Result(String gameIdPrefix, byte[] archive)
        throws Exception {
        Fixture fixture = createFixture(gameIdPrefix, archive);
        try {
            Intent result = launchAndAwait(requestIntent(fixture));
            assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
            assertEquals(
                EngineResult.SCRIPT_ERROR.code(),
                result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(
                34,
                result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
            );
        } finally {
            fixture.delete();
        }
    }

    private Fixture createFixture(String gameIdPrefix, byte[] archive)
        throws Exception {
        String gameId = gameIdPrefix + android.os.SystemClock.elapsedRealtime();
        File root = new File(context.getFilesDir(), "krkr-xp3-fixtures/" + gameId);
        File saveDirectory = new File(context.getFilesDir(), "saves/" + gameId);
        deleteTree(root);
        deleteTree(saveDirectory);
        assertTrue(root.mkdirs());
        Files.write(new File(root, "data.xp3").toPath(), archive);
        return new Fixture(gameId, root, saveDirectory);
    }

    private Intent requestIntent(Fixture fixture) {
        EngineLaunchRequest request = new EngineLaunchRequest(
            fixture.gameId,
            Uri.fromFile(fixture.root),
            fixture.saveDirectory.getAbsolutePath(),
            EngineType.KRKR,
            Bundle.EMPTY
        );
        return new Intent(context, KrkrEngineActivity.class)
            .putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request);
    }

    private Intent launchAndAwait(Intent intent) throws Exception {
        instrumentation.runOnMainSync(() -> host.launchEngine(intent));
        assertTrue(host.awaitEngineResult(15, TimeUnit.SECONDS));
        Intent data = host.engineResultData();
        assertNotNull(data);
        return data;
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

    private static final class Fixture {
        final String gameId;
        final File root;
        final File saveDirectory;

        Fixture(String gameId, File root, File saveDirectory) {
            this.gameId = gameId;
            this.root = root;
            this.saveDirectory = saveDirectory;
        }

        void delete() throws Exception {
            deleteTree(root);
            deleteTree(saveDirectory);
        }
    }
}
