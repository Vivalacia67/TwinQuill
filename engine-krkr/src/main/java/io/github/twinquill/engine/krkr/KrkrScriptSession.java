/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-private TJS owner; callbacks run on one bounded worker, never the UI/GL thread. */
final class KrkrScriptSession {
    static final int NORMAL_EXIT_REQUESTED = 100;
    static final int EVENT_TOUCH = 0;
    static final int EVENT_KEY = 1;
    static final int EVENT_PAUSE = 2;
    static final int EVENT_RESUME = 3;
    static final int EVENT_LOW_MEMORY = 4;
    static final int EVENT_SURFACE_CHANGED = 5;
    private static final ConcurrentHashMap<Long, KrkrScriptSession> LIVE =
        new ConcurrentHashMap<>();

    private final long handle;
    private final int sourceKind;
    private final String source;
    private final ThreadPoolExecutor worker;
    private final Object queueLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean activationRequested;
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicLong droppedEvents = new AtomicLong();
    private volatile int workerDiagnostic;

    private KrkrScriptSession(long handle, int sourceKind, String source, boolean deferred) {
        this.handle = handle;
        this.sourceKind = sourceKind;
        this.source = source;
        activationRequested = new AtomicBoolean(!deferred);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            operation -> new Thread(operation, "TwinQuill-Krkr-Script"),
            new ThreadPoolExecutor.AbortPolicy());
    }

    /** Called from the broker startup worker (or an instrumentation worker). */
    static KrkrScriptSession start(int sourceKind, String source) {
        return create(sourceKind, source, false);
    }

    /** Reserve and decode the source, leaving startup for the ready Android host. */
    static KrkrScriptSession prepare(int sourceKind, String source) {
        return create(sourceKind, source, true);
    }

    private static KrkrScriptSession create(int sourceKind, String source, boolean deferred) {
        System.loadLibrary("twinquill_engine_krkr");
        for (KrkrScriptSession previous : LIVE.values()) {
            if (previous.closed.get()) {
                try {
                    if (!previous.released.await(5, TimeUnit.SECONDS)) {
                        throw new StartupException(41);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new StartupException(41);
                }
            }
        }
        long handle = deferred ? nativePrepare(sourceKind, source) : nativeStart(sourceKind, source);
        if (handle <= 0) throw new StartupException(handle < 0 ? (int) -handle : 21);
        try {
            KrkrScriptSession session = new KrkrScriptSession(handle, sourceKind, source, deferred);
            LIVE.put(handle, session);
            return session;
        } catch (RuntimeException | Error exception) {
            nativeClose(handle);
            throw exception;
        }
    }

    static KrkrScriptSession require(KrkrRuntimeRequest request) {
        KrkrScriptSession session = LIVE.get(request.scriptHandle());
        if (session == null || session.closed.get() || !session.matches(request)) {
            throw new IllegalArgumentException("Missing or stale Krkr script session");
        }
        return session;
    }

    boolean matches(KrkrRuntimeRequest request) {
        return sourceKind == request.sourceKind() && source.equals(request.source());
    }

    long handle() { return handle; }

    void hostReady(int width, int height) {
        synchronized (queueLock) {
            if (closed.get() || width <= 0 || height <= 0) return;
            if (!activationRequested.compareAndSet(false, true)) return;
            worker.execute(() -> {
                if (closed.get()) return;
                try {
                    int result = nativeActivate(handle, width, height);
                    if (result != 0 && result != NORMAL_EXIT_REQUESTED) workerDiagnostic = result;
                } catch (RuntimeException | LinkageError exception) { workerDiagnostic = 41; }
            });
        }
    }

    void event(int kind, double... args) {
        synchronized (queueLock) {
            if (closed.get() || !activationRequested.get()) return;
            try {
                worker.execute(() -> {
                    if (closed.get() || workerDiagnostic != 0) return;
                    try {
                        int result = nativeEvent(handle, kind, args);
                        if (result != 0 && result != NORMAL_EXIT_REQUESTED) workerDiagnostic = result;
                    } catch (RuntimeException | LinkageError exception) { workerDiagnostic = 41; }
                });
            } catch (RejectedExecutionException exception) { droppedEvents.incrementAndGet(); }
        }
    }

    void tick() {
        synchronized (queueLock) {
            if (closed.get() || !activationRequested.get()
                || !tickQueued.compareAndSet(false, true)) return;
            try {
                worker.execute(() -> {
                    try {
                        if (!closed.get() && workerDiagnostic == 0) {
                            int result = nativeEvent(handle, 6, new double[0]);
                            if (result != 0 && result != NORMAL_EXIT_REQUESTED) workerDiagnostic = result;
                        }
                    } catch (RuntimeException | LinkageError exception) { workerDiagnostic = 41; }
                    finally { tickQueued.set(false); }
                });
            } catch (RejectedExecutionException exception) { tickQueued.set(false); }
        }
    }

    int poll(long runtimeHandle) {
        if (closed.get()) return 11;
        if (workerDiagnostic != 0) return workerDiagnostic;
        return nativePoll(handle, runtimeHandle);
    }

    void close() {
        synchronized (queueLock) {
            if (!closed.compareAndSet(false, true)) return;
            nativeCancel(handle);
            // Closing must not be dropped behind a full event queue. Discard pending
            // callbacks and release the engine after the currently running callback.
            worker.getQueue().clear();
            worker.execute(() -> {
                try { nativeClose(handle); }
                finally {
                    LIVE.remove(handle, this);
                    released.countDown();
                }
            });
            worker.shutdown();
        }
    }

    long droppedEvents() { return droppedEvents.get(); }
    long[] stats() { return nativeStats(handle); }
    static long[] globalStats() { return nativeStats(0); }
    static boolean hasLiveSessions() { return !LIVE.isEmpty(); }

    static final class StartupException extends RuntimeException {
        final int diagnostic;
        StartupException(int diagnostic) {
            super("Krkr script startup failed: " + diagnostic);
            this.diagnostic = diagnostic;
        }
    }

    private static native long nativeStart(int sourceKind, String source);
    static native void nativeSetAssets(android.content.res.AssetManager assets);
    private static native long nativePrepare(int sourceKind, String source);
    static native int nativeActivate(long handle, int width, int height);
    private static native void nativeCancel(long handle);
    static native int nativeEvent(long handle, int kind, double[] args);
    private static native int nativePoll(long handle, long runtimeHandle);
    static native void nativeClose(long handle);
    private static native long[] nativeStats(long handle);
}
