/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.krkr.KrkrRuntimeActivity;
import io.github.twinquill.engine.ons.OnsRuntimeActivity;
import io.github.twinquill.launcher.data.GameEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the real launcher task, including a root opened by an explicit adb-style intent. */
@RunWith(AndroidJUnit4.class)
public final class LauncherTaskResumeInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private int taskId = -1;
    private String grantRoot;
    private Uri grant;
    private final List<File> ownedDirectories = new ArrayList<>();

    @Before
    public void createLauncherTask() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        taskId = main.getTaskId();
    }

    @After
    public void removeOwnedTaskAndFixtures() throws Exception {
        ActivityManager.AppTask task = task();
        if (task != null) task.finishAndRemoveTask();
        if (grant != null) {
            context.getContentResolver().releasePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.getContentResolver().call(LauncherGrantBrokerProvider.AUTHORITY,
                LauncherGrantBrokerProvider.METHOD_REVOKE, grantRoot, null);
        }
        for (File directory : ownedDirectories) {
            if (!directory.exists()) continue;
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    public void resumesLooseKrkrStateAfterLauncherAndRecentTaskReturn() throws Exception {
        checkKrkrReturn("m3-loose");
    }

    @Test
    public void resumesCompressedKrkrStateAfterLauncherAndRecentTaskReturn() throws Exception {
        checkKrkrReturn("m3-compressed");
    }

    @Test
    public void keepsOnsRuntimeWhenLauncherIntentIsRepeated() throws Exception {
        File root = new File(context.getCacheDir(), "launcher-ons-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        ownedDirectories.add(root);
        Files.write(new File(root, "0.txt").toPath(),
            "*define\ngame\n*start\ndelay 120000\nend\n".getBytes(StandardCharsets.UTF_8));
        launch(game("ons-reentry", Uri.fromFile(root), EngineType.ONS), EngineType.ONS);
        awaitTop(OnsRuntimeActivity.class);
        int pid = awaitProcessPid(context.getPackageName() + ":ons_runtime");
        homeAndReturnThroughLauncher();
        awaitTop(OnsRuntimeActivity.class);
        assertEquals(pid, processPid(context.getPackageName() + ":ons_runtime"));
        assertEquals("Duplicate launcher covered the ONS runtime", 3, task().getTaskInfo().numActivities);
    }

    private void checkKrkrReturn(String root) throws Exception {
        grantRoot = root;
        Bundle result = context.getContentResolver().call(LauncherGrantBrokerProvider.AUTHORITY,
            LauncherGrantBrokerProvider.METHOD_GRANT, root, null);
        grant = result.getParcelable("uri");
        context.getContentResolver().takePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        GameEntity first = game(root, grant, EngineType.KRKR);
        launch(first, EngineType.KRKR);
        awaitTop(KrkrRuntimeActivity.class);
        awaitCenterColor(108, 34, 56); // Initial alpha-composed M2 rectangle.
        tapCenter();
        awaitCenterColor(32, 180, 80);
        int pid = awaitProcessPid(context.getPackageName() + ":krkr");
        for (int pass = 0; pass < 2; ++pass) {
            homeAndReturnThroughLauncher();
            awaitTop(KrkrRuntimeActivity.class);
            awaitCenterColor(32, 180, 80);
            assertEquals(pid, processPid(context.getPackageName() + ":krkr"));
            assertEquals("Duplicate launcher covered the running game", 3, task().getTaskInfo().numActivities);
        }
        assertTrue(instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        Thread.sleep(3000);
        task().moveToFront(); // The existing task route used by Recents.
        awaitTop(KrkrRuntimeActivity.class);
        awaitCenterColor(32, 180, 80);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitTop(MainActivity.class);

        // A fresh game must start after normal exit, instead of INVALID_REQUEST.
        launch(game(root + "-next", grant, EngineType.KRKR), EngineType.KRKR);
        awaitTop(KrkrRuntimeActivity.class);
        awaitCenterColor(108, 34, 56);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitTop(MainActivity.class);
        assertEquals(1, task().getTaskInfo().numActivities);
    }

    private GameEntity game(String name, Uri root, EngineType type) {
        String id = "launcher-reentry-" + UUID.randomUUID();
        ownedDirectories.add(new File(context.getFilesDir(), "saves/" + id));
        return new GameEntity(id, name, root.toString(), type.name(), type.name(),
            1.0, "[]", null, null, System.currentTimeMillis(), null, "{}", true);
    }

    private void launch(GameEntity game, EngineType type) throws Exception {
        AtomicReference<MainActivity> main = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (main.get() == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (var activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MainActivity && activity.getTaskId() == taskId) main.set((MainActivity) activity);
                }
            });
            if (main.get() == null) Thread.sleep(50);
        }
        assertNotNull("Launcher did not resume after engine exit", main.get());
        instrumentation.runOnMainSync(() -> {
            main.get().startActivityForResult(EngineRouter.INSTANCE.createIntent(context, game, type), 47);
        });
    }

    private void homeAndReturnThroughLauncher() throws Exception {
        assertTrue(instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        Thread.sleep(3000);
        // Use a system launcher intent, not AppTask.startActivity's special
        // in-task behavior, which can bypass creation of the duplicate entry.
        try (var descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                + "-f 0x10200000 -n io.github.twinquill/.launcher.MainActivity");
                var output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            output.readAllBytes();
        }
    }

    private ActivityManager.AppTask task() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            var info = task.getTaskInfo();
            int id = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
            if (id == taskId) return task;
        }
        return null;
    }

    private void awaitTop(Class<?> expected) throws Exception {
        ComponentName component = new ComponentName(context, expected);
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < deadline) {
            ActivityManager.AppTask task = task();
            if (task != null && component.equals(task.getTaskInfo().topActivity)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected task top " + component + ", actual " +
            (task() == null ? "missing task" : task().getTaskInfo().topActivity));
    }

    private Bitmap screenshot() {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Could not capture running game", image);
        return image;
    }

    private void tapCenter() {
        Bitmap image = screenshot();
        float x = image.getWidth() / 2f, y = image.getHeight() / 2f;
        image.recycle();
        long time = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0);
            try { assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
        }
    }

    private void awaitCenterColor(int red, int green, int blue) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        int actual = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bitmap image = screenshot();
            actual = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
            image.recycle();
            if (Math.abs(Color.red(actual) - red) <= 2 && Math.abs(Color.green(actual) - green) <= 2
                && Math.abs(Color.blue(actual) - blue) <= 2) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Game state pixel mismatch: " + Integer.toHexString(actual));
    }

    private int processPid(String name) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (var process : manager.getRunningAppProcesses()) if (name.equals(process.processName)) return process.pid;
        return 0;
    }

    private int awaitProcessPid(String name) throws Exception {
        // The task's top ActivityRecord is visible before its process is ready.
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            int pid = processPid(name);
            if (pid > 0) return pid;
            Thread.sleep(50);
        }
        throw new AssertionError("Runtime process did not start: " + name);
    }
}
