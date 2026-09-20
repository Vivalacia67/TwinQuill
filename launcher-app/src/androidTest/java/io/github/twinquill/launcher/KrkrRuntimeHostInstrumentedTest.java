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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
    }

    @After
    public void tearDown() {
        if (runtime != null && !runtime.isFinishing()) {
            instrumentation.runOnMainSync(runtime::onBackPressed);
        }
    }

    @Test
    public void rendersInputAndBoundedLifecycleThenReleasesHandle() throws Exception {
        String processName = Application.getProcessName();
        assertEquals("io.github.twinquill:krkr", processName);
        String gameId = "krkr-host-" + android.os.SystemClock.elapsedRealtime();
        File fixture = new File(context.getCacheDir(), gameId);
        assertTrue(fixture.mkdirs() || fixture.isDirectory());
        File startup = new File(fixture, "startup.tjs");
        Files.write(startup.toPath(), "// host proof\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File save = new File(context.getFilesDir(), "saves/" + gameId);
        KrkrRuntimeRequest request = KrkrRuntimeRequest.fromBroker(
            context,
            KrkrRuntimeRequest.SOURCE_LOOSE,
            startup.getPath(),
            save.getPath(),
            gameId
        );
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

    private int centerPixel(KrkrGLSurfaceView sourceView) throws Exception {
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
        assertEquals(PixelCopy.SUCCESS, viewResult[0]);
        return viewBitmap.getPixel(viewBitmap.getWidth() / 2, viewBitmap.getHeight() / 2);
    }

    private void waitForColor(KrkrGLSurfaceView sourceView, int red, int green, int blue)
        throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        int lastPixel = 0;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            lastPixel = centerPixel(sourceView);
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
