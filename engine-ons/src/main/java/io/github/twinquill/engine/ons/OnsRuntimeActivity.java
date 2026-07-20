/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineResult;

/** Non-exported SDL runtime reached only through {@link OnsEngineActivity}. */
public final class OnsRuntimeActivity extends ONScripter {
    private static final long PROCESS_EXIT_DELAY_MILLIS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setResult(
            RESULT_OK,
            EngineContract.resultData(EngineResult.NORMAL_EXIT)
        );
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        int runtimePid = android.os.Process.myPid();
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(runtimePid),
            PROCESS_EXIT_DELAY_MILLIS
        );
    }
}
