/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** M0 Krkr source-build entry point hosted in the app-private {@code :krkr} process. */
public final class KrkrEngineActivity extends Activity {
    public static final String EXTRA_GAME_ROOT =
        "io.github.twinquill.extra.KRKR_GAME_ROOT";
    public static final String EXTRA_RESULT_CODE =
        "io.github.twinquill.extra.KRKR_RESULT_CODE";

    private static final String LOG_TAG = "TwinQuill/KrkrActivity";

    static {
        System.loadLibrary("twinquill_engine_krkr");
    }

    private static native int nativeRunLooseStartup(String startupPath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final File startup;
        try {
            startup = resolveStartup(getIntent().getStringExtra(EXTRA_GAME_ROOT));
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid loose game root", exception);
            finishWithResult(14);
            return;
        }

        Thread runner = new Thread(() -> {
            int result = nativeRunLooseStartup(startup.getAbsolutePath());
            Log.i(LOG_TAG, "Loose startup completed with result " + result);
            runOnUiThread(() -> finishWithResult(result));
        }, "TwinQuill-Krkr-M0");
        runner.start();
    }

    private static File resolveStartup(String gameRoot) throws IOException {
        if (gameRoot == null || gameRoot.isBlank()) {
            throw new IllegalArgumentException("Missing game root");
        }
        File root = new File(gameRoot).getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("Game root is not a directory");
        }
        File startup = new File(root, "startup.tjs").getCanonicalFile();
        if (!root.equals(startup.getParentFile()) || !startup.isFile()) {
            throw new IllegalArgumentException("Missing root startup.tjs");
        }
        return startup;
    }

    private void finishWithResult(int result) {
        Intent data = new Intent().putExtra(EXTRA_RESULT_CODE, result);
        setResult(result == 0 ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }
}
