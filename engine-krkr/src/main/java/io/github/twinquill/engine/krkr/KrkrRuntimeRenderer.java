/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.opengl.GLSurfaceView;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** First-party GLES2 renderer and bounded native lifecycle bridge. */
final class KrkrRuntimeRenderer implements GLSurfaceView.Renderer {
    static final int COUNTER_FRAME_COUNT = 0;
    static final int COUNTER_INPUT_GENERATION = 1;
    static final int COUNTER_PAUSE_COUNT = 2;
    static final int COUNTER_RESUME_COUNT = 3;
    static final int COUNTER_LOW_MEMORY_COUNT = 4;
    static final int COUNTER_SURFACE_GENERATION = 5;
    static final int COUNTER_SURFACE_LOSS_COUNT = 6;
    static final int COUNTER_DESTROY_COUNT = 7;
    static final int COUNTER_DROPPED_INPUT_COUNT = 8;
    static final int COUNTER_QUEUED_INPUT_COUNT = 9;
    static final int COUNTER_REJECTED_INPUT_COUNT = 10;
    static final int COUNTER_COUNT = 11;

    private static final long LIFECYCLE_TIMEOUT_MILLIS = 750;
    private static final int INPUT_QUEUE_CAPACITY = 64;

    private final long handle;
    private final WeakReference<RuntimeFailureListener> failureListener;
    private final AtomicBoolean destroyRequested = new AtomicBoolean();
    private final AtomicBoolean nativeDestroyed = new AtomicBoolean();
    private final AtomicInteger inputInFlight = new AtomicInteger();
    private final AtomicLong javaDroppedInput = new AtomicLong();
    private volatile long observedInputGeneration;
    private final AtomicInteger acceptedUntilDraw = new AtomicInteger();
    private volatile KrkrGLSurfaceView view;

    KrkrRuntimeRenderer(
        KrkrRuntimeRequest request,
        WeakReference<RuntimeFailureListener> failureListener
    ) {
        this.failureListener = failureListener;
        long created = nativeCreate(
            request.sourceKind(),
            request.source(),
            request.saveDirectory(),
            request.gameId()
        );
        if (created <= 0L) {
            throw new IllegalStateException("Krkr native runtime handle creation failed");
        }
        handle = created;
        long[] initialCounters = nativeCounters(handle);
        if (initialCounters != null && initialCounters.length == COUNTER_COUNT) {
            observedInputGeneration = initialCounters[COUNTER_INPUT_GENERATION];
        }
    }

    void attachView(KrkrGLSurfaceView view) {
        this.view = view;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (destroyRequested.get() || isDestroyed()) {
            return;
        }
        try {
            int result = nativeSurfaceCreated(handle);
            if (result != 0) {
                reportFailure(result);
                return;
            }
        } catch (RuntimeException | LinkageError exception) {
            reportFailure(41);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (destroyRequested.get() || isDestroyed()) {
            return;
        }
        try {
            int result = nativeSurfaceChanged(handle, width, height);
            if (result != 0) {
                reportFailure(result);
            }
        } catch (RuntimeException | LinkageError exception) {
            reportFailure(41);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (destroyRequested.get() || isDestroyed()) {
            return;
        }
        try {
            int diagnostic = nativeDrawFrame(handle);
            releaseDrainedInputs();
            checkDiagnostic(diagnostic);
        } catch (RuntimeException | LinkageError exception) {
            reportFailure(41);
        }
    }

    void enqueueTouch(
        int actionMasked,
        int pointerId,
        float x,
        float y,
        long eventTime
    ) {
        queueInput(() -> nativeTouch(
            handle,
            actionMasked,
            pointerId,
            x,
            y,
            eventTime
        ));
    }

    void enqueueKey(
        boolean down,
        int keyCode,
        int unicodeCodePoint,
        int metaState,
        int repeatCount,
        long eventTime
    ) {
        queueInput(() -> nativeKey(
            handle,
            down,
            keyCode,
            unicodeCodePoint,
            metaState,
            repeatCount,
            eventTime
        ));
    }

    void dropInput() {
        dropInput(1);
    }

    void dropInput(int count) {
        if (count <= 0) {
            return;
        }
        javaDroppedInput.updateAndGet(value -> saturatingAdd(value, count));
    }

    void pauseAndWait() {
        queueLifecycle(() -> checkDiagnostic(nativePause(handle)), false);
    }

    void resumeAndWait() {
        queueLifecycle(() -> checkDiagnostic(nativeResume(handle)), false);
    }

    void lowMemoryAndWait() {
        queueLifecycle(() -> checkDiagnostic(nativeLowMemory(handle)), false);
    }

    void surfaceLostAndWait() {
        queueLifecycle(() -> checkDiagnostic(nativeSurfaceLost(handle)), false);
    }

    void destroyAndWait() {
        if (!destroyRequested.compareAndSet(false, true)) {
            return;
        }
        queueLifecycle(() -> {
            if (!isDestroyed()) {
                checkDiagnostic(nativeSurfaceLost(handle));
                destroyNativeOnce();
            }
        }, true);
    }

    long[] counters() {
        final long[] counters;
        try {
            counters = nativeCounters(handle);
        } catch (RuntimeException | LinkageError exception) {
            return javaCountersOnly();
        }
        if (counters == null || counters.length != COUNTER_COUNT) {
            return javaCountersOnly();
        }
        counters[COUNTER_DROPPED_INPUT_COUNT] = saturatingAdd(
            counters[COUNTER_DROPPED_INPUT_COUNT],
            javaDroppedInput.get()
        );
        counters[COUNTER_QUEUED_INPUT_COUNT] = inputInFlight.get();
        return counters;
    }

    static long[] globalCounters() {
        try {
            long[] counters = nativeCounters(0L);
            if (counters == null || counters.length != COUNTER_COUNT) {
                return new long[COUNTER_COUNT];
            }
            return counters;
        } catch (RuntimeException | LinkageError exception) {
            return new long[COUNTER_COUNT];
        }
    }

    long handleForTest() {
        return handle;
    }

    private boolean isDestroyed() {
        return nativeDestroyed.get();
    }

    private long[] javaCountersOnly() {
        long[] counters = new long[COUNTER_COUNT];
        counters[COUNTER_DROPPED_INPUT_COUNT] = javaDroppedInput.get();
        counters[COUNTER_QUEUED_INPUT_COUNT] = inputInFlight.get();
        return counters;
    }

    private void queueInput(NativeInput operation) {
        if (!reserveInput()) {
            return;
        }
        KrkrGLSurfaceView currentView = view;
        if (currentView == null || destroyRequested.get() || isDestroyed()) {
            releaseInputPermit();
            dropInput();
            return;
        }
        try {
            currentView.queueEvent(() -> {
                if (destroyRequested.get() || isDestroyed()) {
                    releaseInputPermit();
                    dropInput();
                    return;
                }
                try {
                    int diagnostic = operation.invoke();
                    if (diagnostic == 0) {
                        acceptedUntilDraw.incrementAndGet();
                    } else {
                        releaseInputPermit();
                        checkDiagnostic(diagnostic);
                    }
                } catch (RuntimeException | LinkageError exception) {
                    releaseInputPermit();
                    reportFailure(41);
                }
            });
        } catch (RuntimeException | LinkageError exception) {
            releaseInputPermit();
            dropInput();
            reportFailure(41);
        }
    }

    private boolean reserveInput() {
        while (true) {
            int current = inputInFlight.get();
            if (current >= INPUT_QUEUE_CAPACITY) {
                dropInput();
                return false;
            }
            if (inputInFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseInputPermit() {
        inputInFlight.updateAndGet(value -> value <= 0 ? 0 : value - 1);
    }

    private void releaseDrainedInputs() {
        final long[] counters;
        try {
            counters = nativeCounters(handle);
        } catch (RuntimeException | LinkageError exception) {
            return;
        }
        if (counters == null || counters.length != COUNTER_COUNT) {
            return;
        }
        long generation = counters[COUNTER_INPUT_GENERATION];
        long delta = generation - observedInputGeneration;
        if (delta > 0L) {
            int pending = acceptedUntilDraw.get();
            int release = (int) Math.min(delta, pending);
            if (release > 0) {
                acceptedUntilDraw.updateAndGet(
                    value -> Math.max(0, value - release)
                );
            }
            for (int index = 0; index < release; index++) {
                releaseInputPermit();
            }
        }
        observedInputGeneration = generation;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L || Long.MAX_VALUE - left < right) {
            return right <= 0L ? left : Long.MAX_VALUE;
        }
        return left + right;
    }

    private void queueLifecycle(Runnable operation, boolean destroy) {
        KrkrGLSurfaceView currentView = view;
        if (currentView == null || (destroyRequested.get() || isDestroyed()) && !destroy) {
            if (destroy) {
                destroyNativeOnce();
            } else {
                reportFailure(41);
            }
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        try {
            currentView.queueEvent(() -> {
                if (cancelled.get() || !started.compareAndSet(false, true)) {
                    completed.countDown();
                    return;
                }
                try {
                    operation.run();
                } catch (RuntimeException | LinkageError exception) {
                    if (!destroy) {
                        reportFailure(41);
                    }
                } finally {
                    completed.countDown();
                }
            });
            boolean completedInTime = completed.await(
                LIFECYCLE_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            );
            if (!completedInTime) {
                cancelled.set(true);
                if (destroy) {
                    destroyNativeOnce();
                } else {
                    reportFailure(41);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            if (destroy) {
                destroyNativeOnce();
            } else {
                reportFailure(41);
            }
        } catch (RuntimeException | LinkageError exception) {
            cancelled.set(true);
            if (destroy) {
                destroyNativeOnce();
            } else {
                reportFailure(41);
            }
        }
    }

    private void destroyNativeOnce() {
        if (!nativeDestroyed.compareAndSet(false, true)) {
            return;
        }
        try {
            nativeDestroy(handle);
        } catch (RuntimeException | LinkageError ignored) {
            // The registry has already invalidated this handle.
        } finally {
            acceptedUntilDraw.set(0);
            inputInFlight.set(0);
        }
    }

    private void reportFailure(int diagnostic) {
        RuntimeFailureListener listener = failureListener.get();
        if (listener != null) {
            listener.onRuntimeDiagnostic(diagnostic == 0 ? 41 : diagnostic);
        }
    }

    private void checkDiagnostic(int diagnostic) {
        // Input rejection is an observable bounded-queue outcome, not a
        // reason to tear down a healthy runtime.  Other non-zero diagnostics
        // are surfaced to the Activity exactly once by its finish guard.
        if (diagnostic != 0 && diagnostic != 14) {
            reportFailure(diagnostic);
        }
    }

    interface RuntimeFailureListener {
        void onRuntimeDiagnostic(int diagnostic);
    }

    private interface NativeInput {
        int invoke();
    }

    static native long nativeCreate(
        int sourceKind,
        String source,
        String saveDirectory,
        String gameId
    );

    static native int nativeSurfaceCreated(long handle);

    static native int nativeSurfaceChanged(long handle, int width, int height);

    static native int nativeDrawFrame(long handle);

    static native int nativePause(long handle);

    static native int nativeResume(long handle);

    static native int nativeLowMemory(long handle);

    static native int nativeSurfaceLost(long handle);

    static native int nativeDestroy(long handle);

    static native int nativeTouch(
        long handle,
        int actionMasked,
        int pointerId,
        float x,
        float y,
        long eventTime
    );

    static native int nativeKey(
        long handle,
        boolean down,
        int keyCode,
        int unicodeCodePoint,
        int metaState,
        int repeatCount,
        long eventTime
    );

    static native long[] nativeCounters(long handle);

}
