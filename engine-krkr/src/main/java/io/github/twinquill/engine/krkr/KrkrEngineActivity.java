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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

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
    private static native int nativeRunXp3Startup(String archivePath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LaunchTarget target;
        try {
            target = resolveStartup(getIntent().getStringExtra(EXTRA_GAME_ROOT));
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid loose game root", exception);
            finishWithResult(14);
            return;
        }

        Thread runner = new Thread(() -> {
            int result = target.xp3
                ? nativeRunXp3Startup(target.file.getAbsolutePath())
                : nativeRunLooseStartup(target.file.getAbsolutePath());
            Log.i(LOG_TAG, (target.xp3 ? "XP3" : "Loose")
                + " startup completed with result " + result);
            runOnUiThread(() -> finishWithResult(result));
        }, "TwinQuill-Krkr-M0");
        runner.start();
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
            return new LaunchTarget(startup, false);
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
        return new LaunchTarget(archive, true);
    }

    private void finishWithResult(int result) {
        Intent data = new Intent().putExtra(EXTRA_RESULT_CODE, result);
        setResult(result == 0 ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }

    private static final class LaunchTarget {
        final File file;
        final boolean xp3;

        LaunchTarget(File file, boolean xp3) {
            this.file = file;
            this.xp3 = xp3;
        }
    }
}
