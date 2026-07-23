/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
public final class KrkrSafStorageInstrumentedTest {
    private static final int NO_STARTUP_DIAGNOSTIC = 31;
    private static final int BAD_XP3_HEADER_DIAGNOSTIC = 32;
    private static final int PROTECTED_XP3_DIAGNOSTIC = 35;
    private static final int MALFORMED_XP3_REPEATS = 3;

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
    public void runsLooseStartupFromSafContentRoot() throws Exception {
        Fixture fixture = fixture(LauncherFixtureDocumentsProvider.KRKR_LOOSE_ROOT_ID);
        try {
            assertNormalKrkrExit(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void runsCompressedXp3StartupFromSafContentRoot() throws Exception {
        Fixture fixture = fixture(LauncherFixtureDocumentsProvider.KRKR_XP3_ROOT_ID);
        try {
            assertNormalKrkrExit(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }


    @Test
    public void triesLaterXp3WhenEarlierSafArchiveHasNoStartup()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_XP3_FALLBACK_ROOT_ID
        );
        try {
            assertNormalKrkrExit(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void returnsScriptErrorWhenAllSafXp3ArchivesHaveNoStartup()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_NO_STARTUP_XP3_ROOT_ID
        );
        try {
            assertKrkrScriptError(
                requestIntent(fixture),
                NO_STARTUP_DIAGNOSTIC
            );
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void returnsProtectedDiagnosticForProtectedSafXp3Startup()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_PROTECTED_XP3_ROOT_ID
        );
        try {
            assertKrkrScriptError(
                requestIntent(fixture),
                PROTECTED_XP3_DIAGNOSTIC
            );
        } finally {
            fixture.delete();
        }
    }
    @Test
    public void returnsScriptErrorForMalformedXp3FromSafContentRoot()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_INVALID_XP3_ROOT_ID
        );
        try {
            for (int attempt = 0; attempt < MALFORMED_XP3_REPEATS; attempt++) {
                assertKrkrScriptError(
                    requestIntent(fixture),
                    BAD_XP3_HEADER_DIAGNOSTIC
                );
            }
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void prefersLooseStartupOverInvalidXp3InSafRoot() throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_LOOSE_WITH_INVALID_XP3_ROOT_ID
        );
        try {
            assertNormalKrkrExit(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void selectsFirstXp3ByCaseInsensitiveOrderInSafRoot()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_XP3_ORDER_ROOT_ID
        );
        try {
            assertNormalKrkrExit(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void rejectsCaseInsensitiveStartupAmbiguityInSafRoot()
        throws Exception {
        Fixture fixture = fixture(
            LauncherFixtureDocumentsProvider.KRKR_AMBIGUOUS_STARTUP_ROOT_ID
        );
        try {
            assertInvalidRequest(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    @Test
    public void rejectsSafRootWithoutStartupOrXp3() throws Exception {
        Fixture fixture = fixture(LauncherFixtureDocumentsProvider.KRKR_EMPTY_ROOT_ID);
        try {
            assertInvalidRequest(requestIntent(fixture));
        } finally {
            fixture.delete();
        }
    }

    private EngineProtocolTestHostActivity createHost() {
        Intent intent = new Intent(context, EngineProtocolTestHostActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return (EngineProtocolTestHostActivity) instrumentation.startActivitySync(intent);
    }

    private Fixture fixture(String rootId) {
        String gameId = rootId + "-" + android.os.SystemClock.elapsedRealtime();
        Uri root = grantFixture(rootId);
        File saveDirectory = new File(context.getFilesDir(), "saves/" + gameId);
        return new Fixture(gameId, root, saveDirectory);
    }

    private Uri grantFixture(String rootId) {
        Bundle grant = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_GRANT,
            rootId,
            null
        );
        assertNotNull(grant);
        Uri fixtureRoot = grant.getParcelable("uri", Uri.class);
        assertNotNull(fixtureRoot);
        return fixtureRoot;
    }

    private Intent requestIntent(Fixture fixture) {
        EngineLaunchRequest request = new EngineLaunchRequest(
            fixture.gameId,
            fixture.root,
            fixture.saveDirectory.getAbsolutePath(),
            EngineType.KRKR,
            Bundle.EMPTY
        );
        return new Intent(context, KrkrEngineActivity.class)
            .putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request);
    }

    private void assertNormalKrkrExit(Intent request) throws Exception {
        Intent result = launchAndAwait(request);
        assertEquals(Activity.RESULT_OK, host.engineResultCode());
        assertEquals(
            EngineResult.NORMAL_EXIT.code(),
            result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            0,
            result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
    }

    private void assertInvalidRequest(Intent request) throws Exception {
        Intent result = launchAndAwait(request);
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.INVALID_REQUEST.code(),
            result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertFalse(result.hasExtra(KrkrEngineActivity.EXTRA_RESULT_CODE));
    }

    private void assertKrkrScriptError(Intent request, int diagnostic)
        throws Exception {
        Intent result = launchAndAwait(request);
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.SCRIPT_ERROR.code(),
            result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            diagnostic,
            result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
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
        final Uri root;
        final File saveDirectory;

        Fixture(String gameId, Uri root, File saveDirectory) {
            this.gameId = gameId;
            this.root = root;
            this.saveDirectory = saveDirectory;
        }

        void delete() throws Exception {
            deleteTree(saveDirectory);
        }
    }
}