/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import android.content.Intent;
import android.os.Build;

/** Stable Intent keys shared by the launcher and isolated engine processes. */
public final class EngineContract {
    public static final String EXTRA_LAUNCH_REQUEST =
        "io.github.twinquill.extra.ENGINE_LAUNCH_REQUEST";
    public static final String EXTRA_RESULT =
        "io.github.twinquill.extra.ENGINE_RESULT";

    private EngineContract() {
    }

    /** Reads the typed cross-process request on every supported Android version. */
    @SuppressWarnings("deprecation")
    public static EngineLaunchRequest launchRequest(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                EXTRA_LAUNCH_REQUEST,
                EngineLaunchRequest.class
            );
        }
        return intent.getParcelableExtra(EXTRA_LAUNCH_REQUEST);
    }

    /** Creates result data with the stable numeric engine result code. */
    public static Intent resultData(EngineResult result) {
        return new Intent().putExtra(EXTRA_RESULT, result.code());
    }
}
