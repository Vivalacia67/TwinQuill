/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Device-only proof of the first-party Krkr host seam.  The repository gate
 * compiles this class without claiming that a device or rendered frame exists
 * in the current environment.
 */
@RunWith(AndroidJUnit4.class)
public final class KrkrRuntimeHostInstrumentedTest {
    private static final int PIXEL_TOLERANCE = 8;
    private static final int EXPECTED_COUNTER_COUNT = 11;
    private static final int INPUT_QUEUE_CAPACITY = 64;
    private static final int OVERFLOW_ATTEMPTS = INPUT_QUEUE_CAPACITY + 1;
    private static final int REJECTED_INPUT_DIAGNOSTIC = 14;
    private static final int REJECTED_HANDLE_DIAGNOSTIC = 11;

    private Instrumentation instrumentation;
    private Context context;
    private Activity runtime;
    private KrkrRuntimeRenderer renderer;
    private KrkrGLSurfaceView surface;
    private boolean correctProcess;
    private final List<File> fixtures = new ArrayList<>();
    private final java.util.Map<String,android.net.Uri> m3Grants = new java.util.LinkedHashMap<>();

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        correctProcess = "io.github.twinquill:krkr".equals(instrumentation.getProcessName());
        assertEquals("Rebuild and reinstall the test APK with -PtwinquillKrkrRuntimeInstrumentation=true",
            "io.github.twinquill:krkr", instrumentation.getProcessName());
        context = instrumentation.getTargetContext();
    }

    @After
    public void tearDown() throws Exception {
        if (!correctProcess) return;
        if (runtime != null && !runtime.isFinishing() && !runtime.isDestroyed()) {
            instrumentation.runOnMainSync(runtime::onBackPressed);
        }
        waitForScriptsReleased();
        for (File fixture : fixtures) deleteFixture(fixture);
        for (String root : new ArrayList<>(m3Grants.keySet())) revokeM3(root,m3Grants.get(root));
    }

    @Test
    public void rendersInputAndBoundedLifecycleThenReleasesHandle() throws Exception {
        String processName = Application.getProcessName();
        assertEquals("io.github.twinquill:krkr", processName);
        String gameId = "krkr-host-" + android.os.SystemClock.elapsedRealtime();
        File fixture = new File(context.getCacheDir(), gameId);
        assertTrue(fixture.mkdirs() || fixture.isDirectory());
        fixtures.add(fixture);
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), "// host proof\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File save = new File(context.getFilesDir(), "saves/" + gameId);
        KrkrRuntimeRequest request = KrkrRuntimeRequest.fromBroker(
            context,
            KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getPath(),
            save.getPath(),
            gameId
        ).withScriptSession(KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath()));
        Intent intent = new Intent(context, KrkrRuntimeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        request.putInto(intent);

        runtime = instrumentation.startActivitySync(intent);
        assertNotNull(runtime);
        renderer = renderer(runtime);
        surface = surface(runtime);
        assertEquals(EXPECTED_COUNTER_COUNT, KrkrRuntimeRenderer.COUNTER_COUNT);
        assertEquals(EXPECTED_COUNTER_COUNT, renderer.counters().length);
        waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT, 1L);

        waitForColor(surface, 46, 158, 242);
        long[] beforeInput = renderer.counters();
        MotionEvent down = MotionEvent.obtain(
            0L,
            android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            32f,
            32f,
            0
        );
        instrumentation.runOnMainSync(() -> surface.onTouchEvent(down));
        down.recycle();
        waitForCounter(
            KrkrRuntimeRenderer.COUNTER_INPUT_GENERATION,
            beforeInput[KrkrRuntimeRenderer.COUNTER_INPUT_GENERATION] + 1L
        );
        waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT, 2L);
        waitForColor(surface, 242, 64, 51);

        KeyEvent key = new KeyEvent(
            android.os.SystemClock.uptimeMillis(),
            android.os.SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ENTER,
            0
        );
        instrumentation.runOnMainSync(() -> surface.onKeyDown(key.getKeyCode(), key));
        waitForCounter(
            KrkrRuntimeRenderer.COUNTER_INPUT_GENERATION,
            beforeInput[KrkrRuntimeRenderer.COUNTER_INPUT_GENERATION] + 2L
        );

        instrumentation.runOnMainSync(() -> instrumentation.callActivityOnPause(runtime));
        waitForCounter(KrkrRuntimeRenderer.COUNTER_PAUSE_COUNT, 1L);
        long[] beforeOverflow = renderer.counters();
        instrumentation.runOnMainSync(() -> {
            for (int index = 0; index < OVERFLOW_ATTEMPTS; index++) {
                MotionEvent move = MotionEvent.obtain(
                    0L,
                    android.os.SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    index,
                    index,
                    0
                );
                surface.onTouchEvent(move);
                move.recycle();
            }
        });
        long[] javaOverflow = renderer.counters();
        assertTrue(
            javaOverflow[KrkrRuntimeRenderer.COUNTER_QUEUED_INPUT_COUNT]
                <= INPUT_QUEUE_CAPACITY
        );
        assertTrue(
            javaOverflow[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT]
                > beforeOverflow[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT]
        );
        MotionEvent multiPointerMove = multiPointerMove();
        instrumentation.runOnMainSync(() -> surface.onTouchEvent(multiPointerMove));
        multiPointerMove.recycle();
        long[] pointerOverflow = renderer.counters();
        assertTrue(
            pointerOverflow[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT]
                >= javaOverflow[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT] + 1L
        );

        long nativeDroppedBefore = renderer.counters()[
            KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT
        ];
        long globalRejectedBeforeOverflow = KrkrRuntimeRenderer.globalCounters()[
            KrkrRuntimeRenderer.COUNTER_REJECTED_INPUT_COUNT
        ];
        int lastDiagnostic = 0;
        long handle = renderer.handleForTest();
        for (int index = 0; index < OVERFLOW_ATTEMPTS; index++) {
            lastDiagnostic = KrkrRuntimeRenderer.nativeTouch(
                handle,
                MotionEvent.ACTION_MOVE,
                0,
                index,
                index,
                android.os.SystemClock.uptimeMillis()
            );
        }
        assertEquals(REJECTED_INPUT_DIAGNOSTIC, lastDiagnostic);
        long[] rejected = renderer.counters();
        assertTrue(
            rejected[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT] > nativeDroppedBefore
        );
        assertTrue(
            KrkrRuntimeRenderer.globalCounters()[
                KrkrRuntimeRenderer.COUNTER_REJECTED_INPUT_COUNT
            ] > globalRejectedBeforeOverflow
        );

        instrumentation.runOnMainSync(() -> instrumentation.callActivityOnResume(runtime));
        waitForCounter(KrkrRuntimeRenderer.COUNTER_RESUME_COUNT, 1L);
        instrumentation.runOnMainSync(runtime::onLowMemory);
        waitForCounter(KrkrRuntimeRenderer.COUNTER_LOW_MEMORY_COUNT, 1L);

        long surfaceGeneration = renderer.counters()[
            KrkrRuntimeRenderer.COUNTER_SURFACE_GENERATION
        ];
        long surfaceLosses = renderer.counters()[
            KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT
        ];
        KrkrGLSurfaceView oldSurface = surface;
        View detachedContent = new View(runtime);
        instrumentation.runOnMainSync(() -> runtime.setContentView(detachedContent));
        assertTrue(oldSurface.getParent() == null);
        waitForCounter(
            KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT,
            surfaceLosses + 1L
        );
        KrkrGLSurfaceView replacement = new KrkrGLSurfaceView(runtime, renderer);
        instrumentation.runOnMainSync(() -> runtime.setContentView(replacement));
        surface = replacement;
        assertTrue(oldSurface.getParent() == null);
        assertNotNull(surface.getParent());
        waitForSurface(surface);
        waitForCounter(
            KrkrRuntimeRenderer.COUNTER_SURFACE_GENERATION,
            surfaceGeneration + 1L
        );

        long[] overflow = renderer.counters();
        assertEquals(EXPECTED_COUNTER_COUNT, overflow.length);
        assertTrue(
            overflow[KrkrRuntimeRenderer.COUNTER_QUEUED_INPUT_COUNT]
                <= INPUT_QUEUE_CAPACITY
        );
        assertTrue(
            overflow[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT]
                >= beforeInput[KrkrRuntimeRenderer.COUNTER_DROPPED_INPUT_COUNT]
        );

        instrumentation.runOnMainSync(() -> {
            KeyEvent back = new KeyEvent(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
            );
            assertFalse(surface.onKeyDown(KeyEvent.KEYCODE_BACK, back));
            assertFalse(surface.onKeyUp(KeyEvent.KEYCODE_BACK, back));
        });
        long[] globalBeforeDestroy = KrkrRuntimeRenderer.globalCounters();
        instrumentation.runOnMainSync(runtime::onBackPressed);
        waitForGlobalCounter(
            KrkrRuntimeRenderer.COUNTER_DESTROY_COUNT,
            globalBeforeDestroy[KrkrRuntimeRenderer.COUNTER_DESTROY_COUNT] + 1L
        );
        renderer.destroyAndWait();
        renderer.destroyAndWait();
        assertEquals(
            REJECTED_HANDLE_DIAGNOSTIC,
            KrkrRuntimeRenderer.nativeTouch(
                handle,
                MotionEvent.ACTION_DOWN,
                0,
                0f,
                0f,
                android.os.SystemClock.uptimeMillis()
            )
        );
        assertEquals(REJECTED_HANDLE_DIAGNOSTIC, KrkrRuntimeRenderer.nativeDestroy(handle));
        long[] global = KrkrRuntimeRenderer.globalCounters();
        assertEquals(EXPECTED_COUNTER_COUNT, global.length);
        assertTrue(
            global[KrkrRuntimeRenderer.COUNTER_DESTROY_COUNT]
                >= globalBeforeDestroy[KrkrRuntimeRenderer.COUNTER_DESTROY_COUNT] + 1L
        );
        assertTrue(
            global[KrkrRuntimeRenderer.COUNTER_REJECTED_INPUT_COUNT]
                >= globalBeforeDestroy[KrkrRuntimeRenderer.COUNTER_REJECTED_INPUT_COUNT] + 2L
        );
    }

    @Test
    public void persistsScriptStateAcrossInputLifecycleAndRecreation() throws Exception {
        File fixture = fixture("persistent");
        Files.write(new File(fixture, "helper.tjs").toPath(),
            "global.loaded = \"中文\";".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String script = "Scripts.execStorage(\"helper.tjs\");\n"
            + "Scripts.exec(\"var count = 0; var pauses = 0; var resumes = 0; var memory = 0;\");\n"
            + "if (loaded != \"\\x4e2d\\x6587\" || !Storages.isExistentStorage(\"helper.tjs\") "
            + "|| Storages.isExistentStorage(\"missing.tjs\") || Scripts.eval(\"6*7\") != 42 "
            + "|| System.getTickCount() < 0) throw new Exception(\"native classes\");\n"
            + "Debug.message(loaded); TwinQuillHost.setColor(20,210,40);\n"
            + "TwinQuillHost.onPause = function() { pauses++; };\n"
            + "TwinQuillHost.onResume = function() { resumes++; };\n"
            + "TwinQuillHost.onLowMemory = function() { memory++; };\n"
            + "TwinQuillHost.onTouch = function(action,id,x,y,time) {\n"
            + " if (action == 0) { count++; if (count == 1) TwinQuillHost.setColor(220,170,30);\n"
            + " else { if (count != 2 || pauses < 1 || resumes < 2 || memory != 1) "
            + "throw new Exception(\"state lost\"); System.exit(); } } };\n";
        long[] before = scriptStats();
        KrkrScriptSession session = startRuntime(fixture, script);
        waitForColor(surface, 20, 210, 40);
        touchDown();
        waitForColor(surface, 220, 170, 30);
        instrumentation.runOnMainSync(runtime::onLowMemory);

        Activity previous = runtime;
        AtomicReference<Activity> replacement = new AtomicReference<>();
        Application app = (Application) context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof KrkrRuntimeActivity && activity != previous) replacement.set(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(previous::recreate);
            long deadline = android.os.SystemClock.uptimeMillis() + 10_000;
            while (replacement.get() == null && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertNotNull("runtime was not recreated", replacement.get());
            runtime = replacement.get();
            renderer = renderer(runtime);
            surface = surface(runtime);
            waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT, 1);
            waitForColor(surface, 220, 170, 30);
            assertEquals("startup executed twice", before[1] + 1, scriptStats()[1]);
            assertEquals(session.handle(), KrkrRuntimeRequest.fromIntent(context, runtime.getIntent()).scriptHandle());
            touchDown();
            waitForScriptsReleased();
            assertTrue(runtime.isFinishing() || runtime.isDestroyed());
            assertEquals(before[2] + 1, scriptStats()[2]);
            assertEquals(11, KrkrScriptSession.nativeEvent(session.handle(),
                KrkrScriptSession.EVENT_TOUCH, new double[] {0,0,0,0,0}));
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks); }
    }

    @Test
    public void preservesScriptStateAcrossHomeAndTaskReturn() throws Exception {
        File fixture = fixture("home-return");
        String script = "var count = 0; TwinQuillHost.setColor(20,210,40);\n"
            + "TwinQuillHost.onTouch = function(action,id,x,y,time) {\n"
            + " if (action == 0) { count++; if (count == 1) TwinQuillHost.setColor(220,170,30);\n"
            + " else { if (count != 2) throw new Exception(\"state lost\"); System.exit(); } } };";
        long[] before = scriptStats();
        KrkrScriptSession session = startRuntime(fixture, script);
        waitForColor(surface, 20, 210, 40);
        touchDown();
        waitForColor(surface, 220, 170, 30);
        int taskId = runtime.getTaskId();
        for (int cycle = 0; cycle < 2; cycle++) {
            long[] counters = renderer.counters();
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand("input keyevent 3"))) {
                while (output.read() != -1) { }
            }
            waitForCounter(KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT,
                counters[KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT] + 1);
            long backgroundFrames = renderer.counters()[KrkrRuntimeRenderer.COUNTER_FRAME_COUNT];
            assertFalse("runtime finished while in the background", runtime.isFinishing());
            assertFalse("runtime was destroyed while in the background", runtime.isDestroyed());
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.AppTask matchingTask = null;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (task.getTaskInfo().id == taskId) matchingTask = task;
            }
            assertNotNull("runtime task disappeared", matchingTask);
            ActivityManager.AppTask task = matchingTask;
            // Recent-task selection brings the existing task forward without a new launch intent.
            instrumentation.runOnMainSync(task::moveToFront);
            waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT,
                backgroundFrames + 1);
            waitForColor(surface, 220, 170, 30);
            assertFalse("runtime finished after task return", runtime.isFinishing());
            assertEquals("startup executed again", before[1] + 1, scriptStats()[1]);
            assertEquals(0, session.poll(0));
        }
        touchDown();
        waitForScriptsReleased();
        assertTrue(runtime.isFinishing() || runtime.isDestroyed());
        assertEquals(before[2] + 1, scriptStats()[2]);
    }

    @Test
    public void rejectsUnsafeStorageAndRecursionThenRecoversFromCallbackError() throws Exception {
        File fixture = fixture("errors");
        File outside = fixture("outside");
        File externalScript = new File(outside, "outside.tjs");
        Files.write(externalScript.toPath(), "System.exit();".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(fixture, "escape.tjs").toPath(), externalScript.toPath());
        File startup = new File(fixture, "startup.tjs");
        for (String source : new String[] {
            "Scripts.execStorage(\"../outside.tjs\");",
            "Storages.isExistentStorage(\"file:///etc/passwd\");",
            "Storages.isExistentStorage(\"helper.tjs\u0000ignored\");",
            "Scripts.execStorage(\"escape.tjs\");",
            "Scripts.execStorage(\"missing.tjs\");",
            "function again() { Scripts.exec(\"again();\"); } again();",
            "TwinQuillHost.setColor(-1,0,0);"
        }) {
            Files.write(startup.toPath(), source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            KrkrScriptSession unexpected = null;
            try {
                unexpected = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
                throw new AssertionError("invalid script was accepted: " + source);
            } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
            finally {
                if (unexpected != null) { unexpected.close(); waitForScriptsReleased(); }
            }
            assertEquals(0, scriptStats()[0]);
        }
        Files.write(startup.toPath(), ("TwinQuillHost.onTouch = function() { "
            + "throw new Exception(\"回调错误\"); };").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KrkrScriptSession session = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        try {
            assertEquals(20, KrkrScriptSession.nativeEvent(session.handle(),
                KrkrScriptSession.EVENT_TOUCH, new double[] {0,0,0,0,0}));
            assertEquals(20, session.poll(0));
        } finally { session.close(); waitForScriptsReleased(); }
        KrkrScriptSession recovered = startRuntime(fixture, "TwinQuillHost.setColor(30,40,200);");
        waitForColor(surface, 30, 40, 200);
        KrkrScriptSession.nativeClose(session.handle());
        try {
            KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath() + "\u0000ignored");
            throw new AssertionError("NUL in JNI source name was accepted");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(10, expected.diagnostic); }
        try {
            KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
            throw new AssertionError("overlapping script engines were accepted");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(10, expected.diagnostic); }
        assertEquals(0, recovered.poll(0));
    }

    @Test
    public void releasesRetainedScriptWhenRecreatedRequestBecomesInvalid() throws Exception {
        File fixture = fixture("invalid-recreation");
        long releases = scriptStats()[2];
        startRuntime(fixture, "var value = 7;");
        Files.delete(new File(fixture, "startup.tjs").toPath());
        instrumentation.runOnMainSync(runtime::recreate);
        waitForScriptsReleased();
        assertEquals(releases + 1, scriptStats()[2]);
    }

    @Test
    public void defersStartupUntilSurfaceReadyAndExecutesItOnce() throws Exception {
        File fixture = fixture("deferred");
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), ("if (TwinQuillHost.getSurfaceWidth() <= 1 "
            + "|| TwinQuillHost.getSurfaceHeight() <= 1) throw new Exception(\"host not ready\");"
            + "var starts = 1; TwinQuillHost.setColor(20,180,70);"
            + "TwinQuillHost.onSurfaceChanged = function() { "
            + "if (starts != 1) throw new Exception(\"startup repeated\"); };")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long startups = scriptStats()[1];
        KrkrScriptSession session = KrkrScriptSession.prepare(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        assertEquals("prepare executed startup without a host", startups, scriptStats()[1]);
        assertEquals(0, session.poll(0));
        launchPreparedRuntime(fixture, session);
        waitForColor(surface, 20, 180, 70);
        assertEquals(startups + 1, scriptStats()[1]);
        session.hostReady(surface.getWidth(), surface.getHeight());
        session.hostReady(surface.getWidth(), surface.getHeight());
        Thread.sleep(100);
        assertEquals(startups + 1, scriptStats()[1]);
    }

    @Test
    public void timesOutUncatchableStartupLoopAndCanStartAgain() throws Exception {
        File fixture = fixture("startup-deadline");
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), "while (true) { try { while (true) {} } catch (e) {} }"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long start = android.os.SystemClock.elapsedRealtime();
        try {
            KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
            throw new AssertionError("infinite startup returned successfully");
        } catch (KrkrScriptSession.StartupException expected) {
            assertEquals(20, expected.diagnostic);
        }
        assertTrue("startup did not respect its deadline",
            android.os.SystemClock.elapsedRealtime() - start < 9_000);
        waitForScriptsReleased();
        startRuntime(fixture, "TwinQuillHost.setColor(70,90,210);");
        waitForColor(surface, 70, 90, 210);
    }

    @Test
    public void cancelsRunningCallbackWithoutBlockingUiOrLeakingVm() throws Exception {
        File fixture = fixture("cancel-running");
        KrkrScriptSession session = startRuntime(fixture,
            "TwinQuillHost.onTouch = function() { TwinQuillHost.setColor(200,50,80);"
            + "while (true) { try { while (true) {} } catch (e) {} } };" );
        touchDown();
        waitForColor(surface, 200, 50, 80);
        long start = android.os.SystemClock.elapsedRealtime();
        instrumentation.runOnMainSync(runtime::onBackPressed);
        assertTrue("UI exit blocked on the script worker",
            android.os.SystemClock.elapsedRealtime() - start < 1_500);
        session.close();
        waitForScriptsReleased();
        assertTrue("cancel did not unwind the running callback",
            android.os.SystemClock.elapsedRealtime() - start < 3_000);
        startRuntime(fixture, "TwinQuillHost.setColor(100,220,100);");
        waitForColor(surface, 100, 220, 100);
    }

    @Test
    public void timesOutRunningCallbackAndReleasesVm() throws Exception {
        File fixture = fixture("callback-deadline");
        KrkrScriptSession session = startRuntime(fixture,
            "TwinQuillHost.onTouch = function() { TwinQuillHost.setColor(200,50,80);"
            + "while (true) { try { while (true) {} } catch (e) {} } };" );
        touchDown();
        waitForColor(surface, 200, 50, 80);
        long deadline = android.os.SystemClock.elapsedRealtime() + 6_000;
        while (!runtime.isFinishing() && !runtime.isDestroyed()
            && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        assertTrue("callback timeout did not finish the runtime",
            runtime.isFinishing() || runtime.isDestroyed());
        waitForScriptsReleased();
        assertEquals(11, session.poll(0));
    }

    @Test
    public void dispatchesUpstreamTimerAndPreservesPauseState() throws Exception {
        File fixture = fixture("tvp-timer");
        KrkrScriptSession session = startRuntime(fixture,
            "var ticks = 0, pausedTicks = -1;"
            + "var timer = new Timer(function(e) {"
            + "if (e.type != 'onTimer' || e.target != timer) throw new Exception('bad event');"
            + "ticks++; TwinQuillHost.setColor(210,160,40); }, '');"
            + "timer.interval = 30; timer.enabled = true;"
            + "TwinQuillHost.onPause = function() { pausedTicks = ticks; };"
            + "TwinQuillHost.onResume = function() { if (pausedTicks < 0) return;"
            + "if (ticks != pausedTicks) throw new Exception('timer ran while paused');"
            + "timer.enabled = false; TwinQuillHost.setColor(40,180,70); };" );
        waitForColor(surface, 210, 160, 40);
        instrumentation.runOnMainSync(() -> instrumentation.callActivityOnPause(runtime));
        Thread.sleep(400);
        instrumentation.runOnMainSync(() -> instrumentation.callActivityOnResume(runtime));
        waitForColor(surface, 40, 180, 70);
        assertEquals(0, session.poll(0));
        Thread.sleep(100);
        waitForColor(surface, 40, 180, 70);
    }

    @Test
    public void reportsTimerErrorsAndReleasesItsActionReferences() throws Exception {
        File fixture = fixture("tvp-timer-error");
        startRuntime(fixture,
            "var timer = new Timer(function() { throw new Exception('timer error'); }, '');"
            + "timer.interval = 100;"
            + "TwinQuillHost.onTouch = function() { timer.enabled = true; };" );
        touchDown();
        long deadline = android.os.SystemClock.elapsedRealtime() + 6_000;
        while (!runtime.isFinishing() && !runtime.isDestroyed()
            && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        assertTrue("timer failure did not finish the runtime",
            runtime.isFinishing() || runtime.isDestroyed());
        waitForScriptsReleased();
        startRuntime(fixture, "TwinQuillHost.setColor(80,90,200);");
        waitForColor(surface, 80, 90, 200);
    }

    @Test
    public void rejectsInvalidTimerIntervalsAndExcessTimers() throws Exception {
        File fixture = fixture("tvp-timer-limits");
        File startup = new File(fixture, "startup.tjs");
        for (String script : new String[] {
            "var timer = new Timer(function(){}, ''); timer.interval = -1;",
            "var timer = new Timer(function(){}, ''); timer.interval = 1.0 / 0.0;",
            "var timer = new Timer(function(){}, ''); timer.interval = 86400001;",
            "var timers = []; for (var i=0; i<129; ++i) timers.add(new Timer(function(){}, ''));"
        }) {
            Files.write(startup.toPath(), script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
                throw new AssertionError("invalid Timer was accepted");
            } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
            waitForScriptsReleased();
        }
        startRuntime(fixture, "TwinQuillHost.setColor(80,200,90);");
        waitForColor(surface, 80, 200, 90);
    }

    @Test
    public void releasesThrowingAndInfiniteFinalizersIncludingTimerCycles() throws Exception {
        File fixture = fixture("finalizer-cleanup");
        for (String finalizeBody : new String[] {
            "throw new Exception('finalizer error');", "while (true) {}"
        }) {
            startRuntime(fixture,
                "class BrokenTimer extends Timer { function BrokenTimer() {"
                + "super.Timer(function(){}, ''); } function finalize() { "
                + finalizeBody + " } } var timers=[];"
                + "for (var i=0; i<4; ++i) timers.add(new BrokenTimer());");
            long start = android.os.SystemClock.elapsedRealtime();
            instrumentation.runOnMainSync(runtime::onBackPressed);
            waitForScriptsReleased();
            assertTrue("finalizer blocked shutdown",
                android.os.SystemClock.elapsedRealtime() - start < 5_000);
            // Fill the entire admitted timer budget. Old native instances or
            // self-retaining actions would exhaust it or poison the new VM.
            startRuntime(fixture,
                "var timers=[]; for (var i=0; i<128; ++i) timers.add(new Timer(function(){}, ''));"
                + "timers[0].onTimer = function(){ TwinQuillHost.setColor(50,180,90);"
                + "timers[0].enabled=false; }; timers[0].interval=30; timers[0].enabled=true;");
            waitForColor(surface, 50, 180, 90);
            instrumentation.runOnMainSync(runtime::onBackPressed);
            waitForScriptsReleased();
        }
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), ("class Broken {function Broken(){}"
            + "function finalize(){throw new Exception('explicit invalidation error');}}"
            + "var broken=new Broken(); invalidate broken;")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
            throw new AssertionError("explicit finalizer failure was suppressed outside shutdown");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
        waitForScriptsReleased();
    }

    @Test
    public void waitsForBoundedFinalizerCleanupWhenImmediatelyStartingAgain() throws Exception {
        File fixture = fixture("immediate-relaunch");
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), ("class SlowTimer extends Timer { function SlowTimer() {"
            + "super.Timer(function(){}, ''); } function finalize() { while(true){} } }"
            + "var slow=new SlowTimer();").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KrkrScriptSession previous = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        previous.close();
        Files.write(startup.toPath(), "// new session\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long start = android.os.SystemClock.elapsedRealtime();
        KrkrScriptSession next = KrkrScriptSession.prepare(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        try {
            assertTrue(previous.handle() != next.handle());
            assertEquals(0, next.poll(0));
            assertTrue("relaunch exceeded its release wait",
                android.os.SystemClock.elapsedRealtime() - start < 6_000);
        } finally { next.close(); }
        waitForScriptsReleased();
    }

    @Test
    public void boundsPendingScriptEventsAndAlwaysReleasesOnClose() throws Exception {
        File fixture = fixture("queue");
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), "// queue proof\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KrkrScriptSession session = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        long releases = scriptStats()[2];
        Field field = KrkrScriptSession.class.getDeclaredField("worker");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(session);
        CountDownLatch blocked = new CountDownLatch(1), unblock = new CountDownLatch(1);
        worker.execute(() -> {
            blocked.countDown();
            try { unblock.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; ++i) session.event(KrkrScriptSession.EVENT_RESUME);
            assertTrue(worker.getQueue().size() <= 64);
            assertTrue(session.droppedEvents() > 0);
            session.close();
            session.close();
            assertTrue(worker.getQueue().size() <= 1);
        } finally { unblock.countDown(); session.close(); }
        waitForScriptsReleased();
        assertEquals(releases + 1, scriptStats()[2]);
    }

    @Test
    public void m4BootsPinnedKagFramework() throws Exception {
        File directory = fixture("m4-kag");
        copyM3Asset("m4-loose", directory);
        KrkrScriptSession session = launchM3Storage(2,
            new File(directory,"startup.tjs").getCanonicalPath(),directory.getName());
        waitForM4Color(300,100,40,80,64);
        assertEquals("Pinned KAG startup must succeed", 0, session.poll(0));
        assertM4Text();
        assertAudioAdvances();
        try (BitmapCloser shot = new BitmapCloser(captureSurface());
             java.io.OutputStream out = Files.newOutputStream(new File(context.getExternalFilesDir(null), "m4-opening.png").toPath())) {
            assertTrue(shot.bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
        }
    }

    @Test
    public void m4SafLooseAndXp3PlayBothBranchesAndResumeAudio() throws Exception {
        for (String root : new String[] {"m4-loose","m4-compressed"}) {
            long starts=scriptStats()[1];
            KrkrScriptSession session=launchM3Saf(root,"m4-"+android.os.SystemClock.elapsedRealtime());
            waitForM4Color(300,100,40,80,64);
            assertM4Text(); assertAudioAdvances();
            long lost=renderer.counters()[KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT];
            try(InputStream output=new ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand("input keyevent 3"))) {
                while(output.read()!=-1) {}
            }
            waitForCounter(KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT,lost+1);
            Thread.sleep(150);
            long paused=KrkrPcmAudio.renderedFrames(); Thread.sleep(350);
            assertEquals("Home must pause actual AudioTrack playback",paused,KrkrPcmAudio.renderedFrames());
            ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.AppTask match=null;
            for(ActivityManager.AppTask task:manager.getAppTasks())
                if(task.getTaskInfo().id==runtime.getTaskId())match=task;
            assertNotNull(match); ActivityManager.AppTask front=match;
            instrumentation.runOnMainSync(front::moveToFront);
            waitForM4Color(300,100,40,80,64); assertAudioAdvances();
            assertEquals("KAG startup must run once",starts+1,scriptStats()[1]);
            touchGame(300,400,640,480); Thread.sleep(400);
            assertM4Text();
            touchGame(130,root.equals("m4-loose")?309:339,640,480);
            if(root.equals("m4-loose"))waitForM4Color(300,100,32,64,176);
            else waitForM4Color(300,100,176,96,32);
            waitForM4PageGlyph(); assertM4Text();
            assertEquals(0,session.poll(0));
            touchGame(300,400,640,480); waitForScriptsReleased();
            assertEquals("Audio resources must be released",0,KrkrPcmAudio.activeSoundCount());
            assertTrue(runtime.isFinishing()||runtime.isDestroyed()); runtime=null;
        }
    }

    @Test
    public void m4SlowSafLookupStillInitializesKagAndCompletesRoute() throws Exception {
        KrkrScriptSession session = launchM3Saf("m4-slow",
            "m4-slow-" + android.os.SystemClock.elapsedRealtime());
        waitForM4Color(300,100,40,80,64);
        assertM4Text();
        assertAudioAdvances();
        assertEquals("KAG must initialize within its existing execution budget",0,session.poll(0));
        touchGame(300,400,640,480);
        Thread.sleep(400);
        touchGame(130,309,640,480);
        waitForM4Color(300,100,32,64,176);
        waitForM4PageGlyph();
        assertM4Text();
        touchGame(300,400,640,480);
        waitForScriptsReleased();
        assertEquals(0,KrkrPcmAudio.activeSoundCount());
    }

    @Test
    public void m4SafLookupSnapshotExpiresAndPreservesAmbiguityAndRevocation() throws Exception {
        io.github.twinquill.nativevfs.NativeVfs.install(context);
        context.getContentResolver().call("io.github.twinquill.test.grants", "reset-lookup", null, null);
        android.net.Uri tree = grantM3("m4-batch");
        Class<?> backend = Class.forName("io.github.twinquill.nativevfs.SafVfsBackend");
        java.lang.reflect.Method begin = backend.getDeclaredMethod("beginLookup");
        java.lang.reflect.Method end = backend.getDeclaredMethod("endLookup");
        begin.setAccessible(true);
        end.setAccessible(true);
        begin.invoke(null);
        try {
            assertEquals(-3, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "late.txt")[0]);
            assertEquals(-3, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "absent.txt")[0]);
            assertEquals(1, context.getContentResolver().call("io.github.twinquill.test.grants",
                "lookup-queries", null, null).getInt("count"));
            context.getContentResolver().call("io.github.twinquill.test.grants", "add-lookup-file", null, null);
            assertEquals(-3, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "late.txt")[0]);
        } finally { end.invoke(null); }
        begin.invoke(null);
        try {
            assertEquals(0, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "late.txt")[0]);
            assertEquals(-3, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "CASE.TXT")[0]);
            assertEquals(0, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "case.txt")[0]);
            assertEquals(2, context.getContentResolver().call("io.github.twinquill.test.grants",
                "lookup-queries", null, null).getInt("count"));
            revokeM3("m4-batch", tree);
            assertEquals(-2, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "case.txt")[0]);
        } finally { end.invoke(null); }
        assertEquals(-2, io.github.twinquill.nativevfs.NativeVfs.stat(tree, "late.txt")[0]);
        android.net.Uri many = grantM3("m3-many");
        begin.invoke(null);
        try {
            assertEquals("Snapshot limits must fall back to streaming file lookup", 0,
                io.github.twinquill.nativevfs.NativeVfs.stat(many, "file-4096")[0]);
        } finally { end.invoke(null); }
    }

    @Test
    public void m4ErrorsReleaseResourcesAndAllowNextGame() throws Exception {
        for(String root:new String[]{"m4-missing","m4-script-error"}) {
            KrkrScriptSession failed=launchM3Saf(root,"m4-error-"+android.os.SystemClock.elapsedRealtime());
            long until=android.os.SystemClock.uptimeMillis()+15000;
            while(failed.poll(0)==0 && android.os.SystemClock.uptimeMillis()<until)Thread.sleep(25);
            assertTrue("KAG error must be reported",failed.poll(0)==20 || failed.poll(0)==11);
            waitForScriptsReleased();
            assertEquals(0,KrkrPcmAudio.activeSoundCount()); runtime=null;
        }
        File normal=fixture("after-m4-errors");
        KrkrScriptSession session=startRuntime(normal,"TwinQuillHost.setColor(32,180,80);");
        waitForColor(surface,32,180,80); assertEquals(0,session.poll(0));
        instrumentation.runOnMainSync(runtime::onBackPressed);waitForScriptsReleased();
    }

    @Test
    public void m4PcmCompletionAndUnsupportedFormatsAreExplicit() throws Exception {
        File directory=fixture("m4-audio"); copyM3Asset("m4-loose",directory);
        long before=KrkrPcmAudio.renderedFrames();
        Files.write(new File(directory,"startup.tjs").toPath(), (
            "var w=new Window();w.setInnerSize(320,200);var l=new Layer(w,null);"
            +"l.setSize(320,200);l.setImageSize(320,200);l.fillRect(0,0,320,200,0xff3040c0);l.visible=true;w.visible=true;"
            +"class TestSound extends WaveSoundBuffer {var began=false;function TestSound(){super.WaveSoundBuffer();}function onStatusChanged(s){"
            +"if(s=='play')began=true;if(s=='stop'&&began)l.fillRect(0,0,320,200,0xff20b450);}}"
            +"var sound=new TestSound();sound.open('sound/chime.wav');sound.volume=50000;sound.play();"
            +"w.onKeyDown=function(key){if(key==13)w.close();};").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KrkrScriptSession session=launchM3Storage(2,new File(directory,"startup.tjs").getCanonicalPath(),directory.getName());
        waitForGameColor(160,100,32,180,80);
        assertTrue("PCM EOF must follow actual sample playback",KrkrPcmAudio.renderedFrames()>=before+4000);
        assertEquals(0,session.poll(0)); pressKey(KeyEvent.KEYCODE_ENTER,0);waitForScriptsReleased();runtime=null;
        assertEquals(0,KrkrPcmAudio.activeSoundCount());
        Files.write(new File(directory,"bad.wav").toPath(),new byte[]{1,2,3});
        for(String code:new String[]{
            "var s=new WaveSoundBuffer();s.open('bad.wav');",
            "var s=new WaveSoundBuffer();s.pan=1;",
            "var s=new WaveSoundBuffer();s.fade(0,100);",
            "var s=new WaveSoundBuffer();s.samplePosition=0;",
            "var v=new VideoOverlay();",
            "function again(){again();}again();"}) {
            Files.write(new File(directory,"startup.tjs").toPath(),code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { KrkrScriptSession unexpected=KrkrScriptSession.start(2,new File(directory,"startup.tjs").getCanonicalPath());
                unexpected.close();throw new AssertionError("Unsupported M4 operation accepted: "+code);
            } catch(KrkrScriptSession.StartupException expected) {assertEquals(20,expected.diagnostic);}
            waitForScriptsReleased();assertEquals(0,KrkrPcmAudio.activeSoundCount());
        }
    }

    @Test
    public void m4KagParserNativeLoopIsBoundedAndPrivateDoubleSeparatorIsSafe() throws Exception {
        File directory=fixture("m4-budget");copyM3Asset("m4-loose",directory);
        assertTrue(new File(directory,"system/Initialize.tjs").delete());
        Files.write(new File(directory,"scenario/loop.ks").toPath(),"*loop\n[jump target='*loop']\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File startup=new File(directory,"startup.tjs");
        Files.write(startup.toPath(),("var p=new KAGParser();p.debugLevel=0;p.loadScenario('scenario/loop.ks');p.getNextTag();").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long begin=android.os.SystemClock.uptimeMillis();
        try {KrkrScriptSession unexpected=KrkrScriptSession.start(2,startup.getCanonicalPath());unexpected.close();
            throw new AssertionError("Unbounded native KAG tag loop accepted");
        }catch(KrkrScriptSession.StartupException expected){assertEquals(20,expected.diagnostic);}
        assertTrue("KAG native loop must obey deadline",android.os.SystemClock.uptimeMillis()-begin<8000);
        waitForScriptsReleased();
        Files.write(startup.toPath(),("var p=System.dataPath+'/flags.tjs';Storages.writeText(p,'GOOD');"
            +"if(Storages.readText(p)!='GOOD')throw 'URI joining failed';var rejected=false;"
            +"try{Storages.writeText(System.dataPath+'/../escape.tjs','BAD');}catch(e){rejected=true;}"
            +"if(!rejected)throw 'Traversal accepted';").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File save=new File(context.getFilesDir(),"saves/"+directory.getName());assertTrue(save.mkdirs());fixtures.add(save);
        KrkrScriptSession safe=KrkrScriptSession.start(2,startup.getCanonicalPath(),save.getCanonicalPath());
        try {assertEquals(0,safe.poll(0));assertEquals("GOOD",new String(Files.readAllBytes(new File(save,"krkr/flags.tjs").toPath()),java.nio.charset.StandardCharsets.UTF_8));}
        finally {safe.close();waitForScriptsReleased();}
        try (InputStream init = instrumentation.getContext().getAssets().open("m4-loose/system/Initialize.tjs")) {
            Files.copy(init, new File(directory, "system/Initialize.tjs").toPath());
        }
        Files.write(startup.toPath(), ("var w=new Window();w.setInnerSize(320,200);w.visible=true;"
            + "var t=new Timer(function(){while(true){}},'');t.interval=20;t.enabled=true;")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        begin = android.os.SystemClock.uptimeMillis();
        KrkrScriptSession bounded = launchM3Storage(2, startup.getCanonicalPath(), directory.getName());
        try {
            long deadline = begin + 14000;
            while (bounded.poll(0) == 0 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(25);
            assertEquals("A KAG callback loop must still terminate", 20, bounded.poll(0));
            assertTrue("KAG callback exceeded its wall-clock budget", android.os.SystemClock.uptimeMillis() < deadline);
        } finally { bounded.close(); waitForScriptsReleased(); }
    }

    private void assertAudioAdvances() throws Exception {
        long before=KrkrPcmAudio.renderedFrames();
        long deadline=android.os.SystemClock.uptimeMillis()+4000;
        while(KrkrPcmAudio.renderedFrames()<=before+200 && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(25);
        assertTrue("Android AudioTrack must render PCM frames",KrkrPcmAudio.renderedFrames()>before+200);
    }

    private void assertM4Text() throws Exception {
        try(BitmapCloser shot=new BitmapCloser(captureSurface())) {
            int glyphs=0;
            for(int y=270;y<400;y++)for(int x=26;x<390;x++) {
                int color=gamePixel(shot.bitmap,x,y,640,480);
                if(Color.red(color)>180 && Color.green(color)>180 && Color.blue(color)>180)glyphs++;
            }
            assertTrue("KAG must draw readable glyphs in its message layer",glyphs>100);
        }
    }

    private void waitForM4PageGlyph() throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+15000;
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            try(BitmapCloser shot=new BitmapCloser(captureSurface())) {
                for(int y=270;y<400;y+=2)for(int x=26;x<590;x+=2) {
                    int color=gamePixel(shot.bitmap,x,y,640,480);
                    if(Math.abs(Color.red(color)-240)<=PIXEL_TOLERANCE
                        && Math.abs(Color.green(color)-210)<=PIXEL_TOLERANCE
                        && Math.abs(Color.blue(color)-70)<=PIXEL_TOLERANCE)return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("KAG must finish the page and display its wait glyph");
    }

    private void waitForM4Color(int x,int y,int r,int g,int b) throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+25000; int color=0;
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            try(BitmapCloser shot=new BitmapCloser(captureSurface())) {
                color=gamePixel(shot.bitmap,x,y,640,480);
                if(Math.abs(Color.red(color)-r)<=PIXEL_TOLERANCE && Math.abs(Color.green(color)-g)<=PIXEL_TOLERANCE
                   && Math.abs(Color.blue(color)-b)<=PIXEL_TOLERANCE)return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("M4 pixel "+x+","+y+" was "+Integer.toHexString(color));
    }

    @Test
    public void m3LooseAndCompressedResourcesMatchAcrossLocalAndSaf() throws Exception {
        for (boolean saf : new boolean[] {false, true}) {
            for (String asset : new String[] {"m3-loose", "m3-compressed"}) {
                KrkrScriptSession session;
                if (saf) session = launchM3Saf(asset, "m3-" + asset + "-" + android.os.SystemClock.elapsedRealtime());
                else {
                    File directory = fixture(asset);
                    copyM3Asset(asset, directory);
                    File entry = new File(directory, asset.equals("m3-loose") ? "startup.tjs" : "data.xp3");
                    session = launchM3Storage(asset.equals("m3-loose") ? 2 : 3, entry.getCanonicalPath(), directory.getName());
                }
                waitForGameColor(16, 16, 50, 90, 220);
                waitForGameColor(96, 24, 20, 180, 80);
                waitForGameColor(160, 100, 108, 34, 56);
                assertEquals(0, session.poll(0));
                pressKey(KeyEvent.KEYCODE_ENTER, 0);
                waitForScriptsReleased();
                runtime = null;
            }
        }
    }

    @Test
    public void m3SafPatchesAndExplicitCp932ResolveCorrectly() throws Exception {
        for (String root : new String[] {"m3-patches", "m3-cp932"}) {
            KrkrScriptSession session = launchM3Saf(root, "m3-" + root + "-" + android.os.SystemClock.elapsedRealtime());
            if (root.equals("m3-patches")) waitForGameColor(160, 100, 34, 136, 68);
            else waitForGameColor(160, 100, 102, 51, 153);
            assertEquals(0, session.poll(0));
            pressKey(KeyEvent.KEYCODE_ENTER, 0);
            waitForScriptsReleased(); runtime = null;
        }
    }

    @Test
    public void m3RejectsUnconfiguredAndMalformedLegacyEncoding() throws Exception {
        File directory = fixture("m3-encoding");
        copyM3Asset("m3-cp932", directory);
        File config = new File(directory, "twinquill-krkr.conf");
        assertTrue(config.delete());
        try {
            KrkrScriptSession.start(3, new File(directory,"data.xp3").getCanonicalPath());
            throw new AssertionError("Legacy encoding was guessed without configuration");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
        File startup = new File(directory,"startup.tjs");
        Files.write(startup.toPath(),new byte[] {(byte)0x81});
        Files.write(config.toPath(),"textEncoding=cp932\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            KrkrScriptSession.start(2,startup.getCanonicalPath());
            throw new AssertionError("Malformed CP932 was replaced silently");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
        Files.write(config.toPath(),"textEncoding=cp932\ntextEncoding=utf-8\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            KrkrScriptSession.start(2,startup.getCanonicalPath());
            throw new AssertionError("Duplicate configuration was accepted");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
        waitForScriptsReleased();
    }

    @Test
    public void m3PrivateSavesPersistAcrossVmRestartAndKeepOnsLayout() throws Exception {
        File directory = fixture("m3-save");
        copyM3Asset("m3-save",directory);
        String id = directory.getName();
        File save = new File(context.getFilesDir(),"saves/"+id);
        assertTrue(save.mkdirs()); fixtures.add(save);
        File legacy = new File(save,"ons-legacy.dat");
        Files.write(legacy.toPath(),"ONS".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int run=1;run<=2;++run) {
            KrkrScriptSession session = launchM3Storage(2,new File(directory,"startup.tjs").getCanonicalPath(),id);
            if(run==1) waitForGameColor(160,100,51,102,204);
            else waitForGameColor(160,100,34,170,102);
            assertEquals(0,session.poll(0));
            assertEquals(String.valueOf(run),new String(Files.readAllBytes(new File(save,"krkr/checks/counter.tjs").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("ONS",new String(Files.readAllBytes(legacy.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            pressKey(KeyEvent.KEYCODE_ENTER,0);
            waitForScriptsReleased(); runtime=null;
        }
        String other = id+"-other";
        File otherSave = new File(context.getFilesDir(),"saves/"+other); fixtures.add(otherSave);
        KrkrScriptSession session=launchM3Storage(2,new File(directory,"startup.tjs").getCanonicalPath(),other);
        waitForGameColor(160,100,51,102,204);
        assertEquals("1",new String(Files.readAllBytes(new File(otherSave,"krkr/checks/counter.tjs").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0,session.poll(0));
    }

    @Test
    public void m3RejectsSaveDirectorySymlinkToAnotherGame() throws Exception {
        File directory = fixture("m3-save-link");
        File startup = new File(directory, "startup.tjs");
        Files.write(startup.toPath(), "var accepted=true;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String id = directory.getName();
        File other = new File(context.getFilesDir(), "saves/" + id + "-other");
        assertTrue(other.mkdirs());
        fixtures.add(other);
        File link = new File(context.getFilesDir(), "saves/" + id);
        Files.createSymbolicLink(link.toPath(), other.toPath());
        try {
            KrkrRuntimeRequest.fromBroker(context, 2, startup.getCanonicalPath(), link.getPath(), id);
            throw new AssertionError("Another game's save directory was admitted through a symbolic link");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("game-private"));
        } finally {
            Files.deleteIfExists(link.toPath());
        }
        assertTrue(other.isDirectory());
    }

    @Test
    public void m3FailedTextSerializationKeepsLastValidPrivateFile() throws Exception {
        File directory = fixture("m3-atomic");
        Files.write(new File(directory,"large.bin").toPath(),new byte[1024*1024]);
        File startup = new File(directory,"startup.tjs");
        Files.write(startup.toPath(),
            ("var p=System.dataPath+'last.tjs'; Storages.writeText(p,'GOOD');"
            + "var bytes=Storages.readBytes('large.bin'); var a=[];for(var i=0;i<32;++i)a[i]=bytes;"
            + "var failed=false;try{(Array.saveStruct incontextof a)(p);}catch(e){failed=e.message.indexOf('32 MiB')>=0;}"
            + "if(!failed||Storages.readText(p)!='GOOD')throw 'Partial save committed';").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File save = new File(context.getFilesDir(),"saves/"+directory.getName());
        assertTrue(save.mkdirs()); fixtures.add(save);
        KrkrScriptSession session=KrkrScriptSession.start(2,startup.getCanonicalPath(),save.getCanonicalPath());
        try { assertEquals(0,session.poll(0)); assertEquals("GOOD",new String(Files.readAllBytes(new File(save,"krkr/last.tjs").toPath()),java.nio.charset.StandardCharsets.UTF_8)); }
        finally { session.close(); }
        waitForScriptsReleased();
    }

    @Test
    public void m3BoundsUnknownSizePipeAndRejectsRevokedArchiveCache() throws Exception {
        File many = fixture("m3-many-local");
        File startup = new File(many, "startup.tjs");
        Files.write(startup.toPath(), "var accepted=true;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < 4096; ++i) assertTrue(new File(many, "folder-" + i).mkdir());
        try {
            KrkrScriptSession.start(2, startup.getCanonicalPath());
            throw new AssertionError("Excessive local directory entries were admitted");
        } catch (KrkrScriptSession.StartupException expected) {
            assertEquals(20, expected.diagnostic);
        }
        io.github.twinquill.nativevfs.NativeVfs.install(context);
        for (String root : new String[] {"m3-large","m3-many"}) {
        android.net.Uri large = grantM3(root);
        try {
            try {
                KrkrScriptSession.start(1,large.toString());
                throw new AssertionError("Oversized unknown-size pipe was admitted");
            } catch (KrkrScriptSession.StartupException expected) { assertEquals(20,expected.diagnostic); }
        } finally { revokeM3(root,large); }
        }
        File[] spoolLeaks = new File(context.getCacheDir(), "native-vfs")
            .listFiles((parent, name) -> name.startsWith("tq-vfs-"));
        assertTrue("Rejected SAF stream leaked its spool", spoolLeaks != null && spoolLeaks.length == 0);
        android.net.Uri tree=grantM3("m3-revoke");
        KrkrScriptSession session=KrkrScriptSession.start(1,tree.toString());
        try {
            assertEquals(0,session.poll(0));
            revokeM3("m3-revoke",tree);
            session.event(KrkrScriptSession.EVENT_KEY,0,66,0);
            long deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(session.poll(0)==0&&android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(25);
            assertEquals("cached archive concealed grant revocation",40,session.poll(0));
        } finally {session.close();}
        waitForScriptsReleased();
        try {
            KrkrScriptSession.start(1,tree.toString());
            throw new AssertionError("Revoked archive grant was reused");
        } catch (KrkrScriptSession.StartupException expected) { assertEquals(40,expected.diagnostic); }
    }

    private KrkrScriptSession launchM3Saf(String root,String id) throws Exception {
        io.github.twinquill.nativevfs.NativeVfs.install(context);
        return launchM3Storage(1,grantM3(root).toString(),id);
    }
    private android.net.Uri grantM3(String root) {
        Bundle grant=context.getContentResolver().call("io.github.twinquill.test.grants","grant",root,null);
        android.net.Uri tree=grant.getParcelable("uri");
        context.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        m3Grants.put(root,tree);
        return tree;
    }
    private void revokeM3(String root,android.net.Uri tree) {
        m3Grants.remove(root);
        context.getContentResolver().releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.getContentResolver().call("io.github.twinquill.test.grants","revoke",root,null);
    }
    private KrkrScriptSession launchM3Storage(int kind,String source,String id) throws Exception {
        KrkrRuntimeRequest request=KrkrRuntimeRequest.fromBroker(context,kind,source,
            new File(context.getFilesDir(),"saves/"+id).getPath(),id);
        File saveDirectory = new File(request.saveDirectory());
        if (!fixtures.contains(saveDirectory)) fixtures.add(saveDirectory);
        KrkrScriptSession session=KrkrScriptSession.prepare(kind,source,request.saveDirectory());
        request.withScriptSession(session);
        Intent intent=new Intent(context,KrkrRuntimeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        request.putInto(intent);
        runtime=instrumentation.startActivitySync(intent);
        renderer=renderer(runtime); surface=surface(runtime);
        waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT,1);
        return session;
    }
    private void copyM3Asset(String name,File destination) throws Exception {
        android.content.res.AssetManager assets=instrumentation.getContext().getAssets();
        String[] children=assets.list(name);
        if(children.length>0) {
            assertTrue(destination.isDirectory()||destination.mkdirs());
            for(String child:children)copyM3Asset(name+"/"+child,new File(destination,child));
        } else {
            try(InputStream input=assets.open(name)){Files.write(destination.toPath(),input.readAllBytes());}
        }
    }

    private File fixture(String suffix) throws Exception {
        File fixture = new File(context.getCacheDir(), "krkr-m1-" + suffix + "-"
            + android.os.SystemClock.elapsedRealtime());
        assertTrue(fixture.mkdirs());
        fixtures.add(fixture);
        return fixture;
    }

    private KrkrScriptSession startRuntime(File fixture, String source) throws Exception {
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KrkrScriptSession session = KrkrScriptSession.prepare(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
        launchPreparedRuntime(fixture, session);
        return session;
    }

    private void launchPreparedRuntime(File fixture, KrkrScriptSession session) throws Exception {
        File startup = new File(fixture, "startup.tjs");
        String gameId = fixture.getName();
        KrkrRuntimeRequest request = KrkrRuntimeRequest.fromBroker(context,
            KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath(),
            new File(context.getFilesDir(), "saves/" + gameId).getPath(), gameId).withScriptSession(session);
        Intent intent = new Intent(context, KrkrRuntimeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        request.putInto(intent);
        runtime = instrumentation.startActivitySync(intent);
        renderer = renderer(runtime);
        surface = surface(runtime);
        waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT, 1);
    }

    @Test
    public void rendersStandardImagesTextAlphaAndTimerWithoutProofHost() throws Exception {
        File fixture = standardVisualFixture();
        String script = new String(Files.readAllBytes(new File(fixture, "startup.tjs").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(script.contains("TwinQuillHost."));
        KrkrScriptSession session = startRuntime(fixture, script);
        waitForGameColor(160, 100, 108, 34, 56);
        waitForGameColor(16, 16, 50, 90, 220);
        waitForGameColor(96, 24, 20, 180, 80);
        try (BitmapCloser snapshot = new BitmapCloser(captureSurface())) {
            int textPixels = 0;
            for (int y = 142; y < 184; y++) {
                for (int x = 8; x < 300; x++) {
                    int pixel = gamePixel(snapshot.bitmap, x, y);
                    if (Color.red(pixel) > 180 && Color.green(pixel) > 100
                        && Color.blue(pixel) < 110) textPixels++;
                }
            }
            assertTrue("FreeType text was not rendered", textPixels > 200);
        }
        waitForGameColor(280, 96, 50, 100, 220);
        waitForGameColor(280, 96, 220, 160, 40);
        touchGame(160, 100);
        waitForGameColor(160, 100, 32, 180, 80);
        assertEquals(0, session.poll(0));
    }

    @Test
    public void preservesStandardLayersAcrossHomeAndActivityRecreation() throws Exception {
        File fixture = standardVisualFixture();
        long startups = scriptStats()[1];
        KrkrScriptSession session = startRuntime(fixture,
            new String(Files.readAllBytes(new File(fixture, "startup.tjs").toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        waitForGameColor(160, 100, 108, 34, 56);
        touchGame(160, 100);
        waitForGameColor(160, 100, 32, 180, 80);
        long lost = renderer.counters()[KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT];
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("input keyevent 3"))) {
            while (output.read() != -1) { }
        }
        waitForCounter(KrkrRuntimeRenderer.COUNTER_SURFACE_LOSS_COUNT, lost + 1);
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.AppTask matching = null;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            if (task.getTaskInfo().id == runtime.getTaskId()) matching = task;
        }
        assertNotNull(matching);
        ActivityManager.AppTask task = matching;
        instrumentation.runOnMainSync(task::moveToFront);
        waitForGameColor(160, 100, 32, 180, 80);
        recreateRuntime();
        waitForGameColor(160, 100, 32, 180, 80);
        waitForGameColor(16, 16, 50, 90, 220);
        assertEquals("standard scene startup repeated", startups + 1, scriptStats()[1]);
        assertEquals(0, session.poll(0));
        pressKey(KeyEvent.KEYCODE_ENTER, 0);
        waitForScriptsReleased();
        assertTrue(runtime.isFinishing() || runtime.isDestroyed());
    }

    @Test
    public void rejectsUnsupportedVisualOperationsUnsafeImagesAndInvalidTrees() throws Exception {
        File fixture = fixture("visual-errors");
        Files.write(new File(fixture, "broken.png").toPath(), new byte[] {1,2,3});
        Bitmap oversized = Bitmap.createBitmap(4096, 1, Bitmap.Config.ARGB_8888);
        try (java.io.OutputStream output = Files.newOutputStream(new File(fixture, "oversized.png").toPath())) {
            assertTrue(oversized.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { oversized.recycle(); }
        for (String operation : new String[] {
            "w.showModal();", "w.setInnerSize(4096,200);", "var duplicate=new Window();",
            "l.opacity=256;", "l.type=3;", "l.font.height=0;", "l.font.face='unreviewed font';",
            "l.loadImages('../outside.png');", "l.loadImages('broken.png');", "l.loadImages('oversized.png');",
            "var child=new Layer(w,l); child.parent=child;",
            "l.setPos(2147483647,0,2147483647,10);",
            "l.fillRect(2147483647,0,2147483647,10,0xffffffff);",
            "l.update(2147483647,0,2147483647,10);",
            "var f=new Font(l); invalidate l; var bad=f.height;",
            "var branch=new Layer(w,l); var leaf=new Layer(w,branch);"
                + "var parent=l; for(var i=0;i<30;++i) parent=new Layer(w,parent); branch.parent=parent;",
            "var parent=l; for(var i=0;i<33;++i) parent=new Layer(w,parent);",
            "for(var i=0;i<64;++i) new Layer(w,l);"
        }) {
            File startup = new File(fixture, "startup.tjs");
            Files.write(startup.toPath(), ("var w=new Window(); var l=new Layer(w,null); " + operation)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            KrkrScriptSession unexpected = null;
            try {
                unexpected = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
                throw new AssertionError("invalid visual operation accepted: " + operation);
            } catch (KrkrScriptSession.StartupException expected) {
                assertEquals(operation, 20, expected.diagnostic);
            } finally {
                if (unexpected != null) unexpected.close();
                waitForScriptsReleased();
            }
        }
        startRuntime(fixture, "var w=new Window(); w.setInnerSize(320,200);"
            + "var l=new Layer(w,null); l.setSize(320,200); l.setImageSize(320,200);"
            + "l.fillRect(0,0,320,200,0xff3040c0); l.visible=true; w.visible=true;");
        waitForGameColor(160, 100, 48, 64, 192);
    }

    @Test
    public void antialiasesOpaqueTextAndClearsInvalidatedWindowWithoutProofFallback() throws Exception {
        File fixture = fixture("opaque-text");
        KrkrScriptSession session = startRuntime(fixture,
            "var w=new Window(); w.setInnerSize(320,200); var l=new Layer(w,null);"
            + "l.setSize(320,200); l.setImageSize(320,200); l.fillRect(0,0,320,200,0x00182430);"
            + "l.font.height=24; l.drawText(8,8,'TwinQuill',0xffffff);"
            + "var edges=0; for(var y=8;y<48;++y) for(var x=8;x<150;++x) {"
            + "var p=l.getMainPixel(x,y); if(p!=0x182430 && p!=0xffffff) edges++; }"
            + "if(edges<20) throw new Exception('Opaque text lost antialiasing');"
            + "l.visible=true; w.visible=true; w.onKeyDown=function(key){if(key==13) invalidate w;};");
        waitForGameColor(300, 180, 24, 36, 48);
        pressKey(KeyEvent.KEYCODE_ENTER, 0);
        waitForGameColor(160, 100, 0, 0, 0);
        assertEquals("Explicit invalidation must keep the VM usable", 0, session.poll(0));
    }

    @Test
    public void mapsKeysAndHonorsStandardWindowCloseQuery() throws Exception {
        File fixture = fixture("window-close");
        KrkrScriptSession session = startRuntime(fixture,
            "var allowClose=false; var w=new Window(); w.setInnerSize(320,200);"
            + "var l=new Layer(w,null); l.setSize(320,200); l.setImageSize(320,200);"
            + "l.fillRect(0,0,320,200,0xff3040c0); l.visible=true; w.visible=true;"
            + "w.onCloseQuery=function(){return allowClose;};"
            + "w.onMouseDown=function(x,y){if(x<0||y<0||x>=320||y>=200) throw new Exception('letterbox'); allowClose=true;};"
            + "w.onKeyDown=function(key,shift){if(key==65){if(shift!=7) throw new Exception('shift flags');"
            + "l.fillRect(0,0,320,200,0xff20b450);} else if(key==13) w.close();};");
        waitForGameColor(160, 100, 48, 64, 192);
        pressKey(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_CTRL_ON);
        waitForGameColor(160, 100, 32, 180, 80);
        pressKey(KeyEvent.KEYCODE_ENTER, 0);
        Thread.sleep(250);
        assertEquals("close query veto was ignored", 0, session.poll(0));
        assertFalse(runtime.isFinishing());
        touchDown(); // Black letterbox, ignored by standard Window input.
        Thread.sleep(100);
        assertEquals(0, session.poll(0));
        touchGame(160, 100);
        pressKey(KeyEvent.KEYCODE_ENTER, 0);
        waitForScriptsReleased();
        assertTrue(runtime.isFinishing() || runtime.isDestroyed());
    }

    private File standardVisualFixture() throws Exception {
        File directory = fixture("standard-visual");
        for (String name : new String[] {"startup.tjs", "checker.png", "sample.jpg"}) {
            try (InputStream source = instrumentation.getContext().getAssets().open("visual/" + name)) {
                Files.copy(source, new File(directory, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return directory;
    }

    @Test
    public void dispatchesCachedCancelledAndReentrantUpstreamAsyncTriggers() throws Exception {
        File fixture = fixture("async-trigger");
        KrkrScriptSession session = startRuntime(fixture,
            "var count=0,phase=0; var trigger=new AsyncTrigger(function(e){"
            + "if(e.type!='onFire'||e.target!=trigger) throw new Exception('async event');"
            + "count++; if(phase==1){trigger.trigger();phase=2;}"
            + "else if(phase==2){if(count!=3) throw new Exception('reentry');"
            + "trigger.cancel();TwinQuillHost.setColor(32,180,80);}},'');"
            + "trigger.trigger();trigger.trigger();"
            + "var cancelled=new AsyncTrigger(function(){throw new Exception('cancelled fired');},'');"
            + "cancelled.trigger();cancelled.cancel();cancelled.trigger();cancelled.mode=atmExclusive;"
            + "var control=new Timer(function(){if(count!=1) throw new Exception('cached');"
            + "phase=1;trigger.trigger();control.enabled=false;},'');control.interval=150;control.enabled=true;");
        waitForColor(surface, 32, 180, 80);
        assertEquals(0, session.poll(0));
    }

    @Test
    public void preservesAsyncPriorityUncachedEventsAndRecoversAfterLimits() throws Exception {
        File fixture = fixture("async-priority");
        KrkrScriptSession session = startRuntime(fixture,
            "var order='',count=0;"
            + "var normal=new AsyncTrigger(function(){order+='N';},'');"
            + "var idle=new AsyncTrigger(function(){order+='I';},'');idle.mode=atmAtIdle;"
            + "var exclusive=new AsyncTrigger(function(){order+='X';},'');exclusive.mode=atmExclusive;"
            + "normal.trigger();idle.trigger();exclusive.trigger();"
            + "var uncached=new AsyncTrigger(function(){count++;},'');uncached.cached=false;"
            + "uncached.trigger();uncached.trigger();uncached.trigger();"
            + "var control=new Timer(function(){if(order!='XNI'||count!=3) throw new Exception('async ordering');"
            + "control.enabled=false;TwinQuillHost.setColor(32,180,80);},'');"
            + "control.interval=150;control.enabled=true;");
        waitForColor(surface, 32, 180, 80);
        session.close(); waitForScriptsReleased();
        File startup = new File(fixture, "startup.tjs");
        for (String invalid : new String[] {
            "var a=new AsyncTrigger(function(){},'');a.mode=3;",
            "var items=[];for(var i=0;i<129;i++)items.add(new AsyncTrigger(function(){},''));"
        }) {
            Files.write(startup.toPath(), invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            KrkrScriptSession unexpected = null;
            try {
                unexpected = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE, startup.getCanonicalPath());
                throw new AssertionError("invalid AsyncTrigger accepted");
            } catch (KrkrScriptSession.StartupException expected) { assertEquals(20, expected.diagnostic); }
            finally { if (unexpected != null) unexpected.close(); waitForScriptsReleased(); }
        }
        KrkrScriptSession recovered = startRuntime(fixture,
            "var items=[];for(var i=0;i<128;i++)items.add(new AsyncTrigger(function(){},''));"
            + "items[0].onFire=function(){TwinQuillHost.setColor(50,100,220);};items[0].trigger();");
        waitForColor(surface, 50, 100, 220);
        assertEquals(0, recovered.poll(0));
    }

    private void pressKey(int code, int meta) {
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta);
        instrumentation.runOnMainSync(() -> surface.onKeyDown(code, event));
    }

    private void touchGame(int gameX, int gameY) { touchGame(gameX,gameY,320,200); }
    private void touchGame(int gameX,int gameY,int width,int height) {
        float scale = Math.min(surface.getWidth() / (float)width, surface.getHeight() / (float)height);
        float x = (surface.getWidth() - width * scale) / 2 + (gameX + 0.5f) * scale;
        float y = (surface.getHeight() - height * scale) / 2 + (gameY + 0.5f) * scale;
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + 20, action, x, y, 0);
            try { instrumentation.runOnMainSync(() -> surface.onTouchEvent(event)); }
            finally { event.recycle(); }
        }
    }

    private void recreateRuntime() throws Exception {
        Activity previous = runtime;
        AtomicReference<Activity> replacement = new AtomicReference<>();
        Application app = (Application) context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof KrkrRuntimeActivity && activity != previous) replacement.set(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(previous::recreate);
            long deadline = android.os.SystemClock.uptimeMillis() + 10_000;
            while (replacement.get() == null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(25);
            assertNotNull(replacement.get());
            runtime = replacement.get(); renderer = renderer(runtime); surface = surface(runtime);
            waitForCounter(KrkrRuntimeRenderer.COUNTER_FRAME_COUNT, 1);
        } finally { app.unregisterActivityLifecycleCallbacks(callbacks); }
    }

    private static final class BitmapCloser implements AutoCloseable {
        final Bitmap bitmap;
        BitmapCloser(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override public void close() { bitmap.recycle(); }
    }

    private Bitmap captureSurface() throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!surface.getHolder().getSurface().isValid()) { Thread.sleep(25); continue; }
            Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
            CountDownLatch copied = new CountDownLatch(1);
            int[] result = {-1};
            try {
                PixelCopy.request(surface, bitmap, code -> { result[0] = code; copied.countDown(); },
                    new Handler(Looper.getMainLooper()));
                assertTrue(copied.await(5, TimeUnit.SECONDS));
                if (result[0] == PixelCopy.SUCCESS) return bitmap;
            } catch (IllegalArgumentException ignored) { }
            bitmap.recycle(); Thread.sleep(25);
        }
        throw new AssertionError("display surface could not be copied");
    }

    private static int gamePixel(Bitmap bitmap,int x,int y) { return gamePixel(bitmap,x,y,320,200); }
    private static int gamePixel(Bitmap bitmap,int x,int y,int width,int height) {
        float scale = Math.min(bitmap.getWidth() / (float)width, bitmap.getHeight() / (float)height);
        int px = (int) ((bitmap.getWidth() - width * scale) / 2 + (x + 0.5f) * scale);
        int py = (int) ((bitmap.getHeight() - height * scale) / 2 + (y + 0.5f) * scale);
        return bitmap.getPixel(px, py);
    }

    private void waitForGameColor(int x, int y, int red, int green, int blue) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 8_000;
        int last = 0;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try (BitmapCloser snapshot = new BitmapCloser(captureSurface())) {
                last = gamePixel(snapshot.bitmap, x, y);
                if (Math.abs(Color.red(last) - red) <= PIXEL_TOLERANCE
                    && Math.abs(Color.green(last) - green) <= PIXEL_TOLERANCE
                    && Math.abs(Color.blue(last) - blue) <= PIXEL_TOLERANCE) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("game pixel " + x + "," + y + " expected " + red + "," + green + "," + blue
            + " actual=" + Integer.toHexString(last));
    }

    private void touchDown() {
        MotionEvent event = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN, 32, 32, 0);
        try { instrumentation.runOnMainSync(() -> surface.onTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static long[] scriptStats() {
        System.loadLibrary("twinquill_engine_krkr");
        long[] stats = KrkrScriptSession.globalStats();
        assertNotNull(stats);
        assertEquals(5, stats.length);
        return stats;
    }

    private static void waitForScriptsReleased() throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 10_000;
        while ((scriptStats()[0] != 0 || KrkrScriptSession.hasLiveSessions())
            && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(25);
        assertEquals("script session leaked", 0, scriptStats()[0]);
        assertFalse(KrkrScriptSession.hasLiveSessions());
    }

    private static void deleteFixture(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        Files.deleteIfExists(file.toPath());
    }

    private KrkrRuntimeRenderer renderer(Activity activity) throws Exception {
        Field field = KrkrRuntimeActivity.class.getDeclaredField("renderer");
        field.setAccessible(true);
        return (KrkrRuntimeRenderer) field.get(activity);
    }

    private KrkrGLSurfaceView surface(Activity activity) throws Exception {
        Field field = KrkrRuntimeActivity.class.getDeclaredField("surfaceView");
        field.setAccessible(true);
        return (KrkrGLSurfaceView) field.get(activity);
    }

    private Integer centerPixel(KrkrGLSurfaceView sourceView) throws Exception {
        if (!sourceView.getHolder().getSurface().isValid()) return null;
        Bitmap viewBitmap = Bitmap.createBitmap(
            Math.max(1, sourceView.getWidth()),
            Math.max(1, sourceView.getHeight()),
            Bitmap.Config.ARGB_8888
        );
        CountDownLatch copied = new CountDownLatch(1);
        int[] viewResult = {-1};
        try {
            PixelCopy.request(
                sourceView,
                viewBitmap,
                copyResult -> {
                    viewResult[0] = copyResult;
                    copied.countDown();
                },
                new Handler(Looper.getMainLooper())
            );
        } catch (IllegalArgumentException exception) {
            // Task return can replace the Surface between the validity check and copy.
            viewBitmap.recycle();
            return null;
        }
        assertTrue(copied.await(5, TimeUnit.SECONDS));
        try {
            // A recreated window can have a valid GL surface before its first
            // buffer reaches the compositor. The enclosing color wait retries it.
            if (viewResult[0] != PixelCopy.SUCCESS) return null;
            return viewBitmap.getPixel(viewBitmap.getWidth() / 2, viewBitmap.getHeight() / 2);
        } finally { viewBitmap.recycle(); }
    }

    private void waitForColor(KrkrGLSurfaceView sourceView, int red, int green, int blue)
        throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        int lastPixel = 0;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            Integer pixel = centerPixel(sourceView);
            if (pixel == null) {
                android.os.SystemClock.sleep(50L);
                continue;
            }
            lastPixel = pixel;
            if (Color.alpha(lastPixel) == 255
                && Math.abs(Color.red(lastPixel) - red) <= PIXEL_TOLERANCE
                && Math.abs(Color.green(lastPixel) - green) <= PIXEL_TOLERANCE
                && Math.abs(Color.blue(lastPixel) - blue) <= PIXEL_TOLERANCE) {
                return;
            }
            android.os.SystemClock.sleep(50L);
        }
        throw new AssertionError(
            "expected color " + red + "," + green + "," + blue
                + " but last ARGB=" + Integer.toHexString(lastPixel)
        );
    }

    private static void waitForSurface(KrkrGLSurfaceView view) {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        while (!view.isAttachedToWindow() || !view.getHolder().getSurface().isValid()) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                throw new AssertionError("surface did not become valid");
            }
            android.os.SystemClock.sleep(50L);
        }
    }

    private static MotionEvent multiPointerMove() {
        final int pointerCount = 11;
        MotionEvent.PointerProperties[] properties =
            new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointerCount];
        for (int index = 0; index < pointerCount; index++) {
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = index;
            properties[index] = property;
            MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
            coordinate.x = index;
            coordinate.y = index;
            coordinates[index] = coordinate;
        }
        long now = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            pointerCount,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        );
    }

    private void waitForCounter(int index, long minimum) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000L;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            long[] values = renderer.counters();
            if (values.length == KrkrRuntimeRenderer.COUNTER_COUNT
                && values[index] >= minimum) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue("counter did not reach " + minimum, renderer.counters()[index] >= minimum);
    }

    private void waitForGlobalCounter(int index, long minimum) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000L;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            long[] values = KrkrRuntimeRenderer.globalCounters();
            if (values.length == KrkrRuntimeRenderer.COUNTER_COUNT
                && values[index] >= minimum) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(
            KrkrRuntimeRenderer.globalCounters()[index] >= minimum
        );
    }
}
