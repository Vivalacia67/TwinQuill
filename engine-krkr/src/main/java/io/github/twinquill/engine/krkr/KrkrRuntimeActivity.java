/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineResult;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-exported first-party GLES2 runtime reached only through the Krkr broker. */
public final class KrkrRuntimeActivity extends Activity
    implements KrkrRuntimeRenderer.RuntimeFailureListener {
    private static final String LOG_TAG = "TwinQuill/KrkrRuntime";
    private static final String STATE_FINISHED =
        "io.github.twinquill.engine.krkr.runtime.FINISHED";

    private final AtomicBoolean finished = new AtomicBoolean();
    private KrkrGLSurfaceView surfaceView;
    private KrkrRuntimeRenderer renderer;
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_FINISHED)) {
            finish();
            return;
        }

        try {
            KrkrRuntimeRequest request = KrkrRuntimeRequest.fromIntent(this, getIntent());
            System.loadLibrary("twinquill_engine_krkr");
            renderer = new KrkrRuntimeRenderer(
                request,
                new WeakReference<>(this)
            );
            surfaceView = new KrkrGLSurfaceView(this, renderer);
            setContentView(surfaceView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                backCallback = () -> finishRuntime(0);
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback
                );
            }
        } catch (IllegalArgumentException | java.io.IOException exception) {
            Log.e(LOG_TAG, "Invalid Krkr runtime request", exception);
            finishRuntime(10);
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to initialize Krkr runtime", exception);
            finishRuntime(41);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_FINISHED, finished.get());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        if (renderer != null) {
            renderer.pauseAndWait();
        }
        if (surfaceView != null) {
            surfaceView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) {
            surfaceView.onResume();
        }
        if (renderer != null) {
            renderer.resumeAndWait();
        }
    }

    @Override
    public void onLowMemory() {
        if (renderer != null) {
            renderer.lowMemoryAndWait();
        }
        super.onLowMemory();
    }

    @Override
    public void onBackPressed() {
        finishRuntime(0);
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (renderer != null) {
            renderer.destroyAndWait();
        }
        super.onDestroy();
    }

    @Override
    public void onRuntimeDiagnostic(int diagnostic) {
        runOnUiThread(() -> finishRuntime(diagnostic));
    }

    private void finishRuntime(int diagnostic) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        int safeDiagnostic = diagnostic < 0 ? 41 : diagnostic;
        Intent data = EngineContract.resultData(
            safeDiagnostic == 0 ? EngineResult.NORMAL_EXIT : EngineResult.NATIVE_CRASH
        );
        data.putExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, safeDiagnostic);
        setResult(safeDiagnostic == 0 ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }
}
