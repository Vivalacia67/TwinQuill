/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.ActivityManager;
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
import io.github.twinquill.engine.ons.OnsEngineActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class EngineProcessProtocolInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private EngineProtocolTestHostActivity host;

    @Before
    public void startHost() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        host = createHost();
    }

    private EngineProtocolTestHostActivity createHost() {
        Intent intent = new Intent(context, EngineProtocolTestHostActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return (EngineProtocolTestHostActivity) instrumentation.startActivitySync(intent);
    }

    @After
    public void finishHost() {
        if (host != null) {
            instrumentation.runOnMainSync(host::finish);
        }
    }

    @Test
    public void runsEncodedOnsSafGamesWithBundledFont()
        throws Exception {
        int mainPid = android.os.Process.myPid();
        String[][] fixtures = {
            {LauncherFixtureDocumentsProvider.ONS_UTF8_ROOT_ID, "utf8"},
            {LauncherFixtureDocumentsProvider.ONS_GBK_ROOT_ID, "gbk"},
            {LauncherFixtureDocumentsProvider.ONS_SJIS_ROOT_ID, "sjis"}
        };
        for (String[] fixture : fixtures) {
            Bundle onsArguments = new Bundle();
            onsArguments.putString(OnsEngineActivity.EXTRA_ENCODING, fixture[1]);
            Intent ons = requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                "ons-" + fixture[1] + "-test",
                grantFixture(fixture[0]),
                onsArguments
            );
            Intent onsResult = launchAndAwait(ons);
            assertEquals(Activity.RESULT_OK, host.engineResultCode());
            assertEquals(
                EngineResult.NORMAL_EXIT.code(),
                onsResult.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertTrue(
                waitForProcessToDisappear(
                    context.getPackageName() + ":ons_runtime",
                    10_000
                )
            );
        }

        File fallbackFont = new File(
            context.getFilesDir(),
            "engine-assets/ons/NotoSansCJKsc-Regular-2.004.otf"
        );
        assertTrue(fallbackFont.isFile());
        assertEquals(16_437_364L, fallbackFont.length());
        int onsPid = requireProcessPid(context.getPackageName() + ":ons");

        Intent krkr = requestIntent(KrkrEngineActivity.class, EngineType.KRKR, "krkr-test");
        assertBoundaryResult(krkr);
        int krkrPid = requireProcessPid(context.getPackageName() + ":krkr");

        assertNotEquals(mainPid, onsPid);
        assertNotEquals(mainPid, krkrPid);
        assertNotEquals(onsPid, krkrPid);
    }

    @Test
    public void restoresOnsSaveFromPrivateGameDirectory() throws Exception {
        String gameId = "ons-save-" + android.os.SystemClock.elapsedRealtime();
        Uri gameRoot = grantFixture(
            LauncherFixtureDocumentsProvider.ONS_SAVE_ROOT_ID
        );
        File saveDirectory = new File(context.getFilesDir(), "saves/" + gameId);

        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameId,
                gameRoot,
                Bundle.EMPTY
            )
        );

        File saveFile = new File(saveDirectory, "save1.dat");
        File restoreControl = new File(saveDirectory, "save2.dat");
        File restoredProof = new File(saveDirectory, "save3.dat");
        assertTrue(saveFile.isFile());
        assertTrue(saveFile.length() > 0L);
        assertFalse(restoreControl.exists());
        assertFalse(restoredProof.exists());
        assertEquals(
            saveDirectory.getCanonicalFile(),
            saveFile.getCanonicalFile().getParentFile()
        );
        assertEquals(
            new File(context.getFilesDir(), "saves").getCanonicalFile(),
            saveDirectory.getCanonicalFile().getParentFile()
        );
        byte[] firstSave = Files.readAllBytes(saveFile.toPath());

        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameId,
                gameRoot,
                Bundle.EMPTY
            )
        );

        assertArrayEquals(firstSave, Files.readAllBytes(saveFile.toPath()));
        assertTrue(restoreControl.isFile());
        assertTrue(restoreControl.length() > 0L);
        assertTrue(restoredProof.isFile());
        assertTrue(restoredProof.length() > 0L);
    }

    private void assertNormalOnsExit(Intent request) throws Exception {
        Intent result = launchAndAwait(request);
        assertEquals(Activity.RESULT_OK, host.engineResultCode());
        assertEquals(
            EngineResult.NORMAL_EXIT.code(),
            result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertTrue(
            waitForProcessToDisappear(
                context.getPackageName() + ":ons_runtime",
                10_000
            )
        );
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

    @Test
    public void rejectsRequestForTheWrongEngine() throws Exception {
        Intent mismatch =
            requestIntent(OnsEngineActivity.class, EngineType.KRKR, "mismatch-test");
        Intent data = launchAndAwait(mismatch);
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.INVALID_REQUEST.code(),
            data.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
    }

    @Test
    public void rejectsGameIdsThatEscapeTheSaveDirectory() {
        try {
            new EngineLaunchRequest(
                "../outside",
                Uri.parse("content://io.github.twinquill.fixture/tree/root"),
                new File(context.getFilesDir(), "outside").getAbsolutePath(),
                EngineType.ONS,
                Bundle.EMPTY
            );
            fail("Unsafe game ID was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("safe path segment"));
        }
    }

    @Test
    public void engineProcessDeathLeavesLauncherAndOtherEngineUsable()
        throws Exception {
        int mainPid = android.os.Process.myPid();
        Intent crash = new Intent(context, EngineCrashTestActivity.class);
        instrumentation.runOnMainSync(() -> host.launchEngine(crash));
        assertTrue(
            waitForProcessToDisappear(context.getPackageName() + ":krkr", 10_000)
        );
        assertEquals(mainPid, android.os.Process.myPid());

        Intent ons =
            requestIntent(OnsEngineActivity.class, EngineType.ONS, "post-crash-ons")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(ons);
        int onsPid = waitForProcessToAppear(
            context.getPackageName() + ":ons",
            10_000
        );
        assertNotEquals(0, onsPid);
        assertNotEquals(mainPid, onsPid);
    }

    private boolean waitForProcessToDisappear(String processName, long timeoutMillis)
        throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (findProcessPid(processName) == 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return findProcessPid(processName) == 0;
    }

    private int waitForProcessToAppear(String processName, long timeoutMillis)
        throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            int pid = findProcessPid(processName);
            if (pid != 0) {
                return pid;
            }
            Thread.sleep(50);
        }
        return findProcessPid(processName);
    }

    private Intent requestIntent(
        Class<? extends Activity> activity,
        EngineType engine,
        String gameId
    ) {
        return requestIntent(
            activity,
            engine,
            gameId,
            Uri.parse("content://io.github.twinquill.fixture/tree/root"),
            Bundle.EMPTY
        );
    }

    private Intent requestIntent(
        Class<? extends Activity> activity,
        EngineType engine,
        String gameId,
        Uri root,
        Bundle arguments
    ) {
        File saveDirectory = new File(context.getFilesDir(), "saves/" + gameId);
        EngineLaunchRequest request = new EngineLaunchRequest(
            gameId,
            root,
            saveDirectory.getAbsolutePath(),
            engine,
            arguments
        );
        return new Intent(context, activity)
            .putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request);
    }

    private void assertBoundaryResult(Intent intent) throws Exception {
        Intent data = launchAndAwait(intent);
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.VFS_UNAVAILABLE.code(),
            data.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
    }

    private Intent launchAndAwait(Intent intent) throws Exception {
        instrumentation.runOnMainSync(() -> host.launchEngine(intent));
        assertTrue(host.awaitEngineResult(15, TimeUnit.SECONDS));
        Intent data = host.engineResultData();
        assertNotNull(data);
        return data;
    }

    private int requireProcessPid(String processName) {
        int pid = findProcessPid(processName);
        if (pid != 0) {
            return pid;
        }
        throw new AssertionError("Missing engine process " + processName);
    }

    private int findProcessPid(String processName) {
        ActivityManager manager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo process :
            manager.getRunningAppProcesses()) {
            if (processName.equals(process.processName)) {
                return process.pid;
            }
        }
        return 0;
    }
}
