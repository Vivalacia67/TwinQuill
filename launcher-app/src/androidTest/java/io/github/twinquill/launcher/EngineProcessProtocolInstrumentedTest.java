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
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.krkr.KrkrEngineActivity;
import io.github.twinquill.engine.krkr.KrkrRuntimeActivity;
import io.github.twinquill.engine.ons.OnsEngineActivity;
import io.github.twinquill.engine.ons.OnsRuntimeActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class EngineProcessProtocolInstrumentedTest {
    // Functional smoke tests include cold starts, ARM translation and surface setup.
    private static final long ENGINE_RESULT_TIMEOUT_SECONDS = 60;
    private static final long CLEANUP_TIMEOUT_SECONDS = 15;

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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        return (EngineProtocolTestHostActivity) instrumentation.startActivitySync(intent);
    }

    @After
    public void finishHost() throws InterruptedException {
        if (host != null) {
            EngineProtocolTestHostActivity finishingHost = host;
            instrumentation.runOnMainSync(finishingHost::finishAndRemoveTask);
            assertTrue(
                "Test host task did not finish during cleanup",
                finishingHost.awaitDestroyed(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
            assertTrue(
                "ONS runtime survived removal of its test task",
                waitForProcessToDisappear(
                    context.getPackageName() + ":ons_runtime",
                    TimeUnit.SECONDS.toMillis(CLEANUP_TIMEOUT_SECONDS)
                )
            );
            host = null;
        }
    }

    @Test
    public void removesRunningEngineBeforeStartingNextHost() throws Exception {
        File gameRoot = new File(
            context.getCacheDir(),
            "ons-cleanup-" + android.os.SystemClock.elapsedRealtime()
        );
        assertTrue(gameRoot.mkdirs());
        File script = new File(gameRoot, "0.txt");
        Files.write(
            script.toPath(),
            "*define\ngame\n*start\ndelay 60000\nend\n".getBytes(StandardCharsets.UTF_8)
        );
        try {
            int taskId = host.getTaskId();
            Intent launch = requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameRoot.getName(),
                Uri.fromFile(gameRoot),
                Bundle.EMPTY
            );
            instrumentation.runOnMainSync(() -> host.launchEngine(launch));
            assertTrue(
                "ONS runtime did not start for the cleanup regression",
                waitForTaskTopActivity(
                    taskId,
                    new ComponentName(context, OnsRuntimeActivity.class),
                    context.getPackageName() + ":ons_runtime",
                    TimeUnit.SECONDS.toMillis(ENGINE_RESULT_TIMEOUT_SECONDS)
                )
            );
            assertFalse(host.awaitEngineResult(100, TimeUnit.MILLISECONDS));

            finishHost();
            assertFalse(
                "Removed test task still contains the ONS runtime",
                isTaskTopActivity(taskId, new ComponentName(context, OnsRuntimeActivity.class))
            );
            host = createHost();
            assertNormalOnsExit(
                requestIntent(
                    OnsEngineActivity.class,
                    EngineType.ONS,
                    gameRoot.getName() + "-next",
                    grantFixture(LauncherFixtureDocumentsProvider.ONS_UTF8_ROOT_ID),
                    Bundle.EMPTY
                )
            );
        } finally {
            finishHost();
            deleteFixture(gameRoot);
        }
    }

    private void deleteFixture(File fixture) throws IOException {
        File[] children = fixture.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteFixture(child);
            }
        }
        Files.deleteIfExists(fixture.toPath());
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

        assertNotEquals(mainPid, onsPid);
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

    @Test
    public void readsOnsNsaAndSarArchivesFromSaf() throws Exception {
        String[][] fixtures = {
            {LauncherFixtureDocumentsProvider.ONS_NSA_ROOT_ID, "nsa"},
            {LauncherFixtureDocumentsProvider.ONS_SAR_ROOT_ID, "sar"}
        };
        for (String[] fixture : fixtures) {
            resetArchiveOpenCount(fixture[0]);
            String gameId =
                "ons-" + fixture[1] + "-" + android.os.SystemClock.elapsedRealtime();
            assertNormalOnsExit(
                requestIntent(
                    OnsEngineActivity.class,
                    EngineType.ONS,
                    gameId,
                    grantFixture(fixture[0]),
                    Bundle.EMPTY
                )
            );

            File proofSave = new File(
                context.getFilesDir(),
                "saves/" + gameId + "/save4.dat"
            );
            assertTrue(proofSave.isFile());
            assertTrue(proofSave.length() > 0L);
            assertTrue(archiveOpenCount(fixture[0]) > 0);
        }
    }

    @Test
    public void executesOnsLuaFromSaf() throws Exception {
        String gameId =
            "ons-lua-" + android.os.SystemClock.elapsedRealtime();
        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameId,
                grantFixture(LauncherFixtureDocumentsProvider.ONS_LUA_ROOT_ID),
                Bundle.EMPTY
            )
        );

        File proofSave = new File(
            context.getFilesDir(),
            "saves/" + gameId + "/save5.dat"
        );
        assertTrue(proofSave.isFile());
        assertTrue(proofSave.length() > 0L);
    }

    @Test
    public void playsOnsPcmAudioFromSaf() throws Exception {
        resetAudioOpenCount();
        String gameId =
            "ons-audio-" + android.os.SystemClock.elapsedRealtime();
        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameId,
                grantFixture(LauncherFixtureDocumentsProvider.ONS_AUDIO_ROOT_ID),
                Bundle.EMPTY
            )
        );

        File proofSave = new File(
            context.getFilesDir(),
            "saves/" + gameId + "/save6.dat"
        );
        assertTrue(proofSave.isFile());
        assertTrue(proofSave.length() > 0L);
        assertTrue(audioOpenCount() > 0);
    }

    @Test
    public void playsOnsPlatformVideoFromSaf() throws Exception {
        resetVideoOpenCount();
        String gameId =
            "ons-video-" + android.os.SystemClock.elapsedRealtime();
        long started = android.os.SystemClock.elapsedRealtime();
        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                gameId,
                grantFixture(LauncherFixtureDocumentsProvider.ONS_VIDEO_ROOT_ID),
                Bundle.EMPTY
            )
        );
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;

        File proofSave = new File(
            context.getFilesDir(),
            "saves/" + gameId + "/save7.dat"
        );
        assertTrue(proofSave.isFile());
        assertTrue(proofSave.length() > 0L);
        assertTrue(videoOpenCount() > 0);
        assertTrue("ONS video returned before its final frame", elapsed >= 1_500L);
    }

    private void resetAudioOpenCount() {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_RESET_AUDIO_OPEN_COUNT,
            null,
            null
        );
        assertNotNull(result);
    }

    private int audioOpenCount() {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_AUDIO_OPEN_COUNT,
            null,
            null
        );
        assertNotNull(result);
        return result.getInt("count", 0);
    }

    private void resetVideoOpenCount() {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_RESET_VIDEO_OPEN_COUNT,
            null,
            null
        );
        assertNotNull(result);
    }

    private int videoOpenCount() {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_VIDEO_OPEN_COUNT,
            null,
            null
        );
        assertNotNull(result);
        return result.getInt("count", 0);
    }

    private void resetArchiveOpenCount(String rootId) {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_RESET_ARCHIVE_OPEN_COUNT,
            rootId,
            null
        );
        assertNotNull(result);
    }

    private int archiveOpenCount(String rootId) {
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_ARCHIVE_OPEN_COUNT,
            rootId,
            null
        );
        assertNotNull(result);
        return result.getInt("count", 0);
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
        return grantFixture(context, rootId);
    }

    static Uri grantFixture(Context context, String rootId) {
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
    public void executesKrkrSafStartupInIsolatedProcess() throws Exception {
        assertNormalKrkrExit(
            grantFixture(LauncherFixtureDocumentsProvider.KRKR_ROOT_ID)
        );
    }

    @Test
    public void executesUnicodeKrkrSafScriptsWithoutM0Sentinel() throws Exception {
        for (String rootId : new String[] {
            LauncherFixtureDocumentsProvider.KRKR_UTF8_ROOT_ID,
            LauncherFixtureDocumentsProvider.KRKR_UTF8_BOM_ROOT_ID,
            LauncherFixtureDocumentsProvider.KRKR_UTF16_LE_ROOT_ID,
            LauncherFixtureDocumentsProvider.KRKR_UTF16_BE_ROOT_ID
        }) {
            assertNormalKrkrExit(grantFixture(rootId));
        }
    }

    @Test
    public void mapsKrkrScriptAndEncodingErrorsAndRecovers() throws Exception {
        for (String rootId : new String[] {
            LauncherFixtureDocumentsProvider.KRKR_SCRIPT_ERROR_ROOT_ID,
            LauncherFixtureDocumentsProvider.KRKR_SYNTAX_ERROR_ROOT_ID,
            LauncherFixtureDocumentsProvider.KRKR_ENCODING_ERROR_ROOT_ID
        }) {
            Intent resultIntent = launchAndAwait(
                requestIntent(
                    KrkrEngineActivity.class,
                    EngineType.KRKR,
                    rootId,
                    grantFixture(rootId),
                    Bundle.EMPTY
                )
            );
            assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
            assertEquals(
                EngineResult.SCRIPT_ERROR.code(),
                resultIntent.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(20, resultIntent.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1));
        }
        assertNormalKrkrExit(grantFixture(LauncherFixtureDocumentsProvider.KRKR_UTF8_ROOT_ID));
    }

    @Test
    public void executesUnicodeKrkrLooseStartup() throws Exception {
        File gameRoot = new File(context.getCacheDir(),
            "krkr-unicode-" + android.os.SystemClock.elapsedRealtime());
        assertTrue(gameRoot.mkdirs());
        try {
            Files.write(new File(gameRoot, "startup.tjs").toPath(),
                LauncherFixtureDocumentsProvider.KRKR_UNICODE_SCRIPT.getBytes(StandardCharsets.UTF_8));
            assertNormalKrkrExit(Uri.fromFile(gameRoot));
        } finally {
            finishHost();
            deleteFixture(gameRoot);
        }
    }

    @Test
    public void executesUnicodeKrkrXp3Startup() throws Exception {
        File gameRoot = new File(context.getCacheDir(),
            "krkr-xp3-" + android.os.SystemClock.elapsedRealtime());
        assertTrue(gameRoot.mkdirs());
        try {
            byte[] script = ("\ufeff" + LauncherFixtureDocumentsProvider.KRKR_UNICODE_SCRIPT)
                .getBytes(StandardCharsets.UTF_16BE);
            Files.write(new File(gameRoot, "data.xp3").toPath(),
                LauncherFixtureDocumentsProvider.rawKrkrXp3(script));
            assertNormalKrkrExit(Uri.fromFile(gameRoot));
        } finally {
            finishHost();
            deleteFixture(gameRoot);
        }
    }

    @Test
    public void honorsKrkrSystemExitDuringStartup() throws Exception {
        File gameRoot = new File(context.getCacheDir(), "krkr-exit-"
            + android.os.SystemClock.elapsedRealtime());
        assertTrue(gameRoot.mkdirs());
        try {
            Files.write(new File(gameRoot, "startup.tjs").toPath(),
                "Debug.message(\"正常退出\"); System.exit(0);".getBytes(StandardCharsets.UTF_8));
            Intent result = launchAndAwait(requestIntent(KrkrEngineActivity.class,
                EngineType.KRKR, gameRoot.getName(), Uri.fromFile(gameRoot), Bundle.EMPTY));
            assertEquals(Activity.RESULT_OK, host.engineResultCode());
            assertEquals(EngineResult.NORMAL_EXIT.code(), result.getIntExtra(EngineContract.EXTRA_RESULT, -1));
            assertEquals(0, result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1));
        } finally { finishHost(); deleteFixture(gameRoot); }
    }

    @Test
    public void mapsKrkrCallbackExitAndScriptFailureThenRecovers() throws Exception {
        File gameRoot = new File(context.getCacheDir(), "krkr-callback-"
            + android.os.SystemClock.elapsedRealtime());
        assertTrue(gameRoot.mkdirs());
        try {
            for (boolean fail : new boolean[] {false, true}) {
                String callback = "TwinQuillHost.onKey = function(down,key,unicode,meta,repeat,time) { "
                    + "if (down && key == 66) { "
                    + (fail ? "throw new Exception(\"回调错误\");" : "System.exit();") + " } };";
                Files.write(new File(gameRoot, "startup.tjs").toPath(), callback.getBytes(StandardCharsets.UTF_8));
                Intent launch = requestIntent(KrkrEngineActivity.class, EngineType.KRKR,
                    gameRoot.getName() + (fail ? "-error" : "-exit"),
                    fail ? Uri.fromFile(gameRoot) : grantFixture(LauncherFixtureDocumentsProvider.KRKR_UTF8_ROOT_ID),
                    Bundle.EMPTY);
                int taskId = host.getTaskId();
                ComponentName component = new ComponentName(context, KrkrRuntimeActivity.class);
                String process = context.getPackageName() + ":krkr";
                try {
                    instrumentation.runOnMainSync(() -> host.launchEngine(launch));
                    assertTrue(waitForTaskTopActivity(taskId, component, process,
                        TimeUnit.SECONDS.toMillis(ENGINE_RESULT_TIMEOUT_SECONDS)));
                    assertTrue("Unable to inject Enter into the script host", injectKey(KeyEvent.KEYCODE_ENTER));
                    assertTrue(host.awaitEngineResult(ENGINE_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    Intent result = host.engineResultData();
                    assertNotNull(result);
                    assertEquals(fail ? Activity.RESULT_CANCELED : Activity.RESULT_OK, host.engineResultCode());
                    assertEquals((fail ? EngineResult.SCRIPT_ERROR : EngineResult.NORMAL_EXIT).code(),
                        result.getIntExtra(EngineContract.EXTRA_RESULT, -1));
                    assertEquals(fail ? 20 : 0, result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1));
                } finally { dismissRuntimeIfVisible(taskId, component, process); }
            }
            assertNormalKrkrExit(grantFixture(LauncherFixtureDocumentsProvider.KRKR_UTF8_ROOT_ID));
        } finally { finishHost(); deleteFixture(gameRoot); }
    }

    private void assertNormalKrkrExit(Uri gameRoot) throws Exception {
        int mainPid = android.os.Process.myPid();
        int taskId = host.getTaskId();
        String krkrProcessName = context.getPackageName() + ":krkr";
        ComponentName runtimeComponent = new ComponentName(
            context,
            KrkrRuntimeActivity.class
        );
        try {
            Intent launch = requestIntent(
                KrkrEngineActivity.class,
                EngineType.KRKR,
                "krkr-valid-" + android.os.SystemClock.elapsedRealtime(),
                gameRoot,
                Bundle.EMPTY
            );
            instrumentation.runOnMainSync(() -> host.launchEngine(launch));
            assertTrue(
                "Krkr runtime Activity was not visible in the host task",
                waitForTaskTopActivity(
                    taskId,
                    runtimeComponent,
                    krkrProcessName,
                    TimeUnit.SECONDS.toMillis(ENGINE_RESULT_TIMEOUT_SECONDS)
                )
            );
            int krkrPid = requireProcessPid(krkrProcessName);
            assertNotEquals(mainPid, krkrPid);
            assertTrue("Unable to inject Back into Krkr runtime", injectBackKey());
            assertTrue(host.awaitEngineResult(ENGINE_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            dismissRuntimeIfVisible(taskId, runtimeComponent, krkrProcessName);
        }
        assertEquals(Activity.RESULT_OK, host.engineResultCode());
        Intent resultIntent = host.engineResultData();
        assertNotNull(resultIntent);
        assertEquals(
            EngineResult.NORMAL_EXIT.code(),
            resultIntent.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            0,
            resultIntent.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
        int mainAppPid = requireProcessPid(context.getPackageName());
        int krkrPid = requireProcessPid(context.getPackageName() + ":krkr");
        assertNotEquals(mainAppPid, krkrPid);
    }

    private boolean waitForTaskTopActivity(
        int taskId,
        ComponentName expectedTopActivity,
        String processName,
        long timeoutMillis
    ) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (findProcessPid(processName) != 0
                && isTaskTopActivity(taskId, expectedTopActivity)) {
                return true;
            }
            Thread.sleep(50);
        }
        return findProcessPid(processName) != 0
            && isTaskTopActivity(taskId, expectedTopActivity);
    }

    private boolean isTaskTopActivity(int taskId, ComponentName expectedTopActivity) {
        ActivityManager manager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask appTask : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo taskInfo = appTask.getTaskInfo();
            if (taskInfo != null
                && taskInfoId(taskInfo) == taskId
                && expectedTopActivity.equals(taskInfo.topActivity)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private int taskInfoId(ActivityManager.RecentTaskInfo taskInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return taskInfo.taskId;
        }
        return taskInfo.id;
    }

    private boolean injectBackKey() {
        return injectKey(KeyEvent.KEYCODE_BACK);
    }

    private boolean injectKey(int keyCode) {
        UiAutomation automation = instrumentation.getUiAutomation();
        long downTime = android.os.SystemClock.uptimeMillis();
        boolean downSent = automation.injectInputEvent(
            new KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            ),
            true
        );
        boolean upSent = automation.injectInputEvent(
            new KeyEvent(
                downTime,
                android.os.SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0
            ),
            true
        );
        return downSent && upSent;
    }

    private void dismissRuntimeIfVisible(
        int taskId,
        ComponentName runtimeComponent,
        String processName
    ) {
        try {
            if (waitForTaskTopActivity(taskId, runtimeComponent, processName, 1_000)) {
                injectBackKey();
                host.awaitEngineResult(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void mapsKrkrAmbiguousRootToInvalidRequest() throws Exception {
        Intent resultIntent = launchAndAwait(
            requestIntent(
                KrkrEngineActivity.class,
                EngineType.KRKR,
                "krkr-ambiguous-" + android.os.SystemClock.elapsedRealtime(),
                grantFixture(LauncherFixtureDocumentsProvider.KRKR_AMBIGUOUS_ROOT_ID),
                Bundle.EMPTY
            )
        );
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.INVALID_REQUEST.code(),
            resultIntent.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            10,
            resultIntent.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
    }

    @Test
    public void mapsKrkrMissingStartupToInvalidRequest() throws Exception {
        Intent resultIntent = launchAndAwait(
            requestIntent(
                KrkrEngineActivity.class,
                EngineType.KRKR,
                "krkr-missing-" + android.os.SystemClock.elapsedRealtime(),
                grantFixture(LauncherFixtureDocumentsProvider.KRKR_MISSING_ROOT_ID),
                Bundle.EMPTY
            )
        );
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.INVALID_REQUEST.code(),
            resultIntent.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            11,
            resultIntent.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
    }

    @Test
    public void mapsKrkrRevokedListPermissionToPermissionRevoked() throws Exception {
        Uri revokedRoot = grantFixture(LauncherFixtureDocumentsProvider.KRKR_REVOKED_ROOT_ID);
        Bundle revoke = context.getContentResolver().call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_REVOKE,
            LauncherFixtureDocumentsProvider.KRKR_REVOKED_ROOT_ID,
            null
        );
        assertNotNull(revoke);
        Intent resultIntent = launchAndAwait(
            requestIntent(
                KrkrEngineActivity.class,
                EngineType.KRKR,
                "krkr-revoked-" + android.os.SystemClock.elapsedRealtime(),
                revokedRoot,
                Bundle.EMPTY
            )
        );
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertEquals(
            EngineResult.PERMISSION_REVOKED.code(),
            resultIntent.getIntExtra(EngineContract.EXTRA_RESULT, -1)
        );
        assertEquals(
            40,
            resultIntent.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
        );
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
        assertTrue(host.awaitEngineResult(ENGINE_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
        assertTrue(
            waitForProcessToDisappear(context.getPackageName() + ":krkr", 10_000)
        );
        assertEquals(mainPid, android.os.Process.myPid());

        // Complete a valid launch before the next test creates its host Activity.
        assertNormalOnsExit(
            requestIntent(
                OnsEngineActivity.class,
                EngineType.ONS,
                "post-crash-ons-" + android.os.SystemClock.elapsedRealtime(),
                grantFixture(LauncherFixtureDocumentsProvider.ONS_UTF8_ROOT_ID),
                Bundle.EMPTY
            )
        );
        assertNotEquals(mainPid, requireProcessPid(context.getPackageName() + ":ons"));
        assertEquals(mainPid, android.os.Process.myPid());
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


    private Intent launchAndAwait(Intent intent) throws Exception {
        instrumentation.runOnMainSync(() -> host.launchEngine(intent));
        assertTrue(
            "Engine result timed out after " + ENGINE_RESULT_TIMEOUT_SECONDS
                + " seconds: " + intent.getComponent(),
            host.awaitEngineResult(ENGINE_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        );
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
