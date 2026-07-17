/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Debug-only host for cross-process Activity result instrumentation. */
public final class EngineProtocolTestHostActivity extends Activity {
    private static final int REQUEST_ENGINE = 1;

    private volatile CountDownLatch resultLatch = new CountDownLatch(1);
    private volatile int engineResultCode = RESULT_CANCELED;
    private volatile Intent engineResultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void launchEngine(Intent intent) {
        resultLatch = new CountDownLatch(1);
        engineResultData = null;
        startActivityForResult(intent, REQUEST_ENGINE);
    }

    public boolean awaitEngineResult(long timeout, TimeUnit unit)
        throws InterruptedException {
        return resultLatch.await(timeout, unit);
    }

    public int engineResultCode() {
        return engineResultCode;
    }

    public Intent engineResultData() {
        return engineResultData;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENGINE) {
            engineResultCode = resultCode;
            engineResultData = data;
            resultLatch.countDown();
        }
    }
}
