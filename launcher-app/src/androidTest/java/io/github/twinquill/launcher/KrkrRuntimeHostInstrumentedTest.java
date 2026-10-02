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
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final List<File> fixtures = new ArrayList<>();

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
    }

    @After
    public void tearDown() throws Exception {
        if (runtime != null && !runtime.isFinishing() && !runtime.isDestroyed()) {
            instrumentation.runOnMainSync(runtime::onBackPressed);
        }
        waitForScriptsReleased();
        for (File fixture : fixtures) deleteFixture(fixture);
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
        KrkrScriptSession session = KrkrScriptSession.start(KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getCanonicalPath());
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
        return session;
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
        Bitmap viewBitmap = Bitmap.createBitmap(
            Math.max(1, sourceView.getWidth()),
            Math.max(1, sourceView.getHeight()),
            Bitmap.Config.ARGB_8888
        );
        CountDownLatch copied = new CountDownLatch(1);
        int[] viewResult = {-1};
        PixelCopy.request(
            sourceView,
            viewBitmap,
            copyResult -> {
                viewResult[0] = copyResult;
                copied.countDown();
            },
            new Handler(Looper.getMainLooper())
        );
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
