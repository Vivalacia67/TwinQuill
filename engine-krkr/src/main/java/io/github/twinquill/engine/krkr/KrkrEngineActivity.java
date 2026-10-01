/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.nativevfs.NativeVfs;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Krkr TJS startup broker hosted in the app-private {@code :krkr} process. */
public final class KrkrEngineActivity extends Activity {
    public static final String EXTRA_GAME_ROOT =
        "io.github.twinquill.extra.KRKR_GAME_ROOT";
    public static final String EXTRA_RESULT_CODE =
        "io.github.twinquill.extra.KRKR_RESULT_CODE";

    private static final String LOG_TAG = "TwinQuill/KrkrActivity";
    private static final int REQUEST_RUNTIME = 7;
    private static final String STATE_RUNTIME_LAUNCHED =
        "io.github.twinquill.engine.krkr.RUNTIME_LAUNCHED";

    private final AtomicBoolean runtimeLaunchIssued = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private PreflightCoordinator preflight;
    private LaunchTarget target;

    private static native int nativeRunLooseStartup(String startupPath);
    private static native int nativeRunXp3Startup(String archivePath);
    private static native int nativeRunSafStartup(String treeUri);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final boolean savedRuntimeLaunched = savedInstanceState != null
            && savedInstanceState.getBoolean(STATE_RUNTIME_LAUNCHED);
        final EngineLaunchRequest request;
        try {
            request = EngineContract.launchRequest(getIntent());
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to decode Krkr launch request", exception);
            finishWithResult(EngineResult.INVALID_REQUEST, 10);
            return;
        }
        Object retained = getLastNonConfigurationInstance();
        PreflightCoordinator retainedPreflight = retained instanceof PreflightCoordinator
            ? (PreflightCoordinator) retained
            : null;
        if (savedRuntimeLaunched && retainedPreflight == null) {
            // The runtime child owns the pending result.  Re-creating the
            // broker must never start a second native preflight/runtime pair.
            runtimeLaunchIssued.set(true);
            return;
        }

        try {
            target = launchTarget(getIntent());
            System.loadLibrary("twinquill_engine_krkr");
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid Krkr game root", exception);
            finishWithResult(EngineResult.INVALID_REQUEST, 10);
            return;
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to initialize the Krkr VFS", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE, 41);
            return;
        }

        if (retainedPreflight != null && retainedPreflight.matches(target)) {
            preflight = retainedPreflight;
        } else {
            preflight = new PreflightCoordinator(target);
        }
        runtimeLaunchIssued.set(preflight.runtimeLaunched());
        preflight.attach(this);
        if (!preflight.runtimeLaunched()) {
            preflight.start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_RUNTIME_LAUNCHED, runtimeLaunchIssued.get());
        super.onSaveInstanceState(state);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return preflight;
    }

    @Override
    protected void onDestroy() {
        if (preflight != null) {
            preflight.detach(this);
        }
        super.onDestroy();
    }

    private void onPreflightResult(
        PreflightCoordinator coordinator,
        long generation,
        int result,
        EngineLaunchRequest request
    ) {
        if (preflight != coordinator
            || !coordinator.isCurrent(this, generation)
            || finished.get()) {
            return;
        }
        dispatchPreflightResult(coordinator, generation, result, request);
    }

    private void dispatchPreflightResult(
        PreflightCoordinator coordinator,
        long generation,
        int result,
        EngineLaunchRequest request
    ) {
        if (preflight != coordinator
            || !coordinator.isCurrent(this, generation)
            || finished.get()) {
            return;
        }
        if (request != null && result == 0) {
            if (coordinator.markRuntimeLaunched()) {
                runtimeLaunchIssued.set(true);
                launchRuntime(request, target);
            } else {
                runtimeLaunchIssued.set(true);
            }
            return;
        }
        finishWithResult(mapNativeResult(result), result);
    }

    private void launchRuntime(EngineLaunchRequest request, LaunchTarget target) {
        if (finished.get()) {
            return;
        }
        try {
            int sourceKind = target.saf
                ? KrkrRuntimeRequest.SOURCE_SAF
                : target.xp3
                    ? KrkrRuntimeRequest.SOURCE_XP3
                    : KrkrRuntimeRequest.SOURCE_LOOSE;
            String source = target.saf
                ? target.treeUri
                : target.file.getCanonicalPath();
            KrkrRuntimeRequest runtime = KrkrRuntimeRequest.fromBroker(
                this,
                sourceKind,
                source,
                request.saveDirectoryPath(),
                request.gameId()
            );
            Intent runtimeIntent = new Intent(this, KrkrRuntimeActivity.class);
            runtime.putInto(runtimeIntent);
            runtimeLaunchIssued.set(true);
            startActivityForResult(runtimeIntent, REQUEST_RUNTIME);
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Unable to validate Krkr runtime request", exception);
            finishWithResult(EngineResult.INVALID_REQUEST, 10);
        } catch (RuntimeException exception) {
            Log.e(LOG_TAG, "Unable to start Krkr runtime", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE, 41);
        }
    }

    private LaunchTarget launchTarget(Intent intent) throws IOException {
        EngineLaunchRequest request = EngineContract.launchRequest(intent);
        if (request == null) {
            if (intent == null) {
                throw new IllegalArgumentException("Krkr launch intent is required");
            }
            String legacyRoot = intent.getStringExtra(EXTRA_GAME_ROOT);
            if (legacyRoot == null || legacyRoot.isBlank()) {
                throw new IllegalArgumentException("Missing Krkr game root");
            }
            return resolveStartup(legacyRoot);
        }
        if (request.engineType() != EngineType.KRKR) {
            throw new IllegalArgumentException("Krkr received a non-Krkr request");
        }

        NativeVfs.install(this);
        requirePrivateSaveDirectory(request);
        Uri root = request.gameRootUri();
        if ("content".equals(root.getScheme())) {
            return LaunchTarget.saf(root.toString());
        }
        if (!"file".equals(root.getScheme()) || root.getPath() == null) {
            throw new IllegalArgumentException("Unsupported Krkr game root URI");
        }
        return resolveStartup(root.getPath());
    }

    private void requirePrivateSaveDirectory(EngineLaunchRequest request)
        throws IOException {
        File saveBase = new File(getFilesDir(), "saves").getCanonicalFile();
        File expected = new File(saveBase, request.gameId()).getCanonicalFile();
        File requested = new File(request.saveDirectoryPath()).getCanonicalFile();
        if (!requested.equals(expected)) {
            throw new IllegalArgumentException(
                "Krkr save directory must match the game-private directory"
            );
        }
        if (!requested.isDirectory() && !requested.mkdirs() && !requested.isDirectory()) {
            throw new IOException("Unable to create private save directory");
        }
    }

    private static LaunchTarget fileTarget(File file, boolean xp3) {
        return new LaunchTarget(file, xp3, null);
    }

    private static LaunchTarget resolveStartup(String gameRoot) throws IOException {
        if (gameRoot == null || gameRoot.isBlank()) {
            throw new IllegalArgumentException("Missing game root");
        }
        File root = new File(gameRoot).getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("Game root is not a directory");
        }
        File startup = new File(root, "startup.tjs").getCanonicalFile();
        if (root.equals(startup.getParentFile()) && startup.isFile()) {
            return fileTarget(startup, false);
        }

        File[] archives = root.listFiles(file ->
            file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".xp3"));
        if (archives == null || archives.length == 0) {
            throw new IllegalArgumentException("Missing root startup.tjs or XP3 archive");
        }
        Arrays.sort(archives, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        File archive = archives[0].getCanonicalFile();
        if (!root.equals(archive.getParentFile())) {
            throw new IllegalArgumentException("XP3 path escapes game root");
        }
        return fileTarget(archive, true);
    }

    private static EngineResult mapNativeResult(int result) {
        if (result == 0) return EngineResult.NORMAL_EXIT;
        if (result == 10 || result == 11) return EngineResult.INVALID_REQUEST;
        if (result == 12 || result == 20 || (result >= 30 && result <= 35)) return EngineResult.SCRIPT_ERROR;
        if (result == 40) return EngineResult.PERMISSION_REVOKED;
        if (result == 41) return EngineResult.VFS_UNAVAILABLE;
        if (result == 21 || result == 22) return EngineResult.NATIVE_CRASH;
        return EngineResult.NATIVE_CRASH;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_RUNTIME) {
            return;
        }
        int diagnostic = resultCode == RESULT_OK ? 0 : 21;
        if (data != null && data.hasExtra(EXTRA_RESULT_CODE)) {
            diagnostic = data.getIntExtra(EXTRA_RESULT_CODE, diagnostic);
        }
        finishWithResult(mapNativeResult(diagnostic), diagnostic);
    }

    private void finishWithResult(EngineResult result, Integer diagnosticCode) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Intent data = EngineContract.resultData(result);
        if (diagnosticCode != null) {
            data.putExtra(EXTRA_RESULT_CODE, diagnosticCode);
        }
        setResult(result == EngineResult.NORMAL_EXIT ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }

    private static final class PreflightCoordinator {
        private static final int NO_RESULT = Integer.MIN_VALUE;

        private final LaunchTarget target;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean runtimeLaunched = new AtomicBoolean();
        private final Object lock = new Object();
        private WeakReference<KrkrEngineActivity> owner = new WeakReference<>(null);
        private long generation;
        private boolean completed;
        private int result = NO_RESULT;

        PreflightCoordinator(LaunchTarget target) {
            this.target = target;
        }

        boolean matches(LaunchTarget other) {
            return target.matches(other);
        }

        boolean runtimeLaunched() {
            return runtimeLaunched.get();
        }

        boolean completed() {
            synchronized (lock) {
                return completed;
            }
        }

        Integer completedResult() {
            synchronized (lock) {
                return completed && result != NO_RESULT ? result : null;
            }
        }

        long attach(KrkrEngineActivity activity) {
            final long attachedGeneration;
            final int completedResult;
            synchronized (lock) {
                generation++;
                attachedGeneration = generation;
                owner = new WeakReference<>(activity);
                completedResult = result;
            }
            if (completedResult != NO_RESULT) {
                post(activity, attachedGeneration, completedResult);
            }
            return attachedGeneration;
        }

        void detach(KrkrEngineActivity activity) {
            synchronized (lock) {
                if (owner.get() == activity) {
                    owner.clear();
                    generation++;
                }
            }
        }

        boolean isCurrent(KrkrEngineActivity activity, long expectedGeneration) {
            synchronized (lock) {
                return generation == expectedGeneration && owner.get() == activity;
            }
        }

        void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            final LaunchTarget runTarget = target;
            Thread runner = new Thread(() -> {
                final int nativeResult;
                try {
                    nativeResult = runTarget.runNative();
                } catch (RuntimeException | LinkageError exception) {
                    Log.e(LOG_TAG, "Krkr native preflight failed", exception);
                    complete(41);
                    return;
                }
                Log.i(LOG_TAG, runTarget.kindLabel()
                    + " startup completed with result " + nativeResult);
                complete(nativeResult);
            }, "TwinQuill-Krkr-Startup");
            runner.start();
        }

        boolean markRuntimeLaunched() {
            return runtimeLaunched.compareAndSet(false, true);
        }

        private void complete(int nativeResult) {
            final KrkrEngineActivity attachedOwner;
            final long attachedGeneration;
            synchronized (lock) {
                if (completed) {
                    return;
                }
                completed = true;
                result = nativeResult;
                attachedOwner = owner.get();
                attachedGeneration = generation;
            }
            if (attachedOwner != null) {
                post(attachedOwner, attachedGeneration, nativeResult);
            }
        }

        private void post(
            KrkrEngineActivity activity,
            long attachedGeneration,
            int nativeResult
        ) {
            WeakReference<KrkrEngineActivity> ownerReference =
                new WeakReference<>(activity);
            activity.runOnUiThread(() ->
                {
                    KrkrEngineActivity owner = ownerReference.get();
                    if (owner != null) {
                        owner.onPreflightResult(this, attachedGeneration, nativeResult,
                            owner.currentLaunchRequest());
                    }
                });
        }
    }

    private EngineLaunchRequest currentLaunchRequest() {
        try {
            return EngineContract.launchRequest(getIntent());
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static final class LaunchTarget {
        final File file;
        final boolean xp3;
        final boolean saf;
        final String treeUri;

        private LaunchTarget(File file, boolean xp3, String treeUri) {
            this.file = file;
            this.xp3 = xp3;
            this.saf = treeUri != null;
            this.treeUri = treeUri;
        }

        static LaunchTarget saf(String treeUri) {
            return new LaunchTarget(null, false, treeUri);
        }

        boolean matches(LaunchTarget other) {
            if (other == null || xp3 != other.xp3 || saf != other.saf) {
                return false;
            }
            if (saf) {
                return treeUri.equals(other.treeUri);
            }
            return file != null && other.file != null
                && file.equals(other.file);
        }

        int runNative() {
            if (saf) {
                return nativeRunSafStartup(treeUri);
            }
            if (xp3) {
                return nativeRunXp3Startup(file.getAbsolutePath());
            }
            return nativeRunLooseStartup(file.getAbsolutePath());
        }

        String kindLabel() {
            return saf ? "SAF" : xp3 ? "XP3" : "Loose";
        }
    }
}
