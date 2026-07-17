/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;

/** Debug-only fixture that simulates an unrecoverable native engine crash. */
public final class EngineCrashTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Process.killProcess(Process.myPid());
    }
}
