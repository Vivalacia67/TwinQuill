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

    private static native int nativeRunLooseStartup(String startupPath);
    private static native int nativeRunXp3Startup(String archivePath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LaunchTarget target;
        try {
            String gameRoot = gameRoot(getIntent());
            if (gameRoot == null) {
                finishWithResult(EngineResult.VFS_UNAVAILABLE, null);
                return;
            }
            target = resolveStartup(gameRoot);
            System.loadLibrary("twinquill_engine_krkr");
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid loose game root", exception);
            finishWithResult(EngineResult.INVALID_REQUEST, null);
            return;
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to initialize the Krkr VFS", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE, null);
            return;
        }

        Thread runner = new Thread(() -> {
            int result = target.xp3
                ? nativeRunXp3Startup(target.file.getAbsolutePath())
                : nativeRunLooseStartup(target.file.getAbsolutePath());
            Log.i(LOG_TAG, (target.xp3 ? "XP3" : "Loose")
                + " startup completed with result " + result);
            EngineResult category =
                result == 0 ? EngineResult.NORMAL_EXIT : EngineResult.SCRIPT_ERROR;
            runOnUiThread(() -> finishWithResult(category, result));
        }, "TwinQuill-Krkr-M0");
        runner.start();
    }

    private String gameRoot(Intent intent) throws IOException {
        EngineLaunchRequest request = EngineContract.launchRequest(intent);
        if (request == null) {
            if (intent == null) {
                throw new IllegalArgumentException("Krkr launch intent is required");
            }
            String legacyRoot = intent.getStringExtra(EXTRA_GAME_ROOT);
            if (legacyRoot == null || legacyRoot.isBlank()) {
                throw new IllegalArgumentException("Missing Krkr game root");
            }
            return legacyRoot;
        }
        if (request.engineType() != EngineType.KRKR) {
            throw new IllegalArgumentException("Krkr received a non-Krkr request");
        }

        NativeVfs.install(this);
        requirePrivateSaveDirectory(request);
        Uri root = request.gameRootUri();
        if ("content".equals(root.getScheme())) {
            Log.i(LOG_TAG, "Krkr SAF runtime integration is scheduled for M3");
            return null;
        }
        if (!"file".equals(root.getScheme()) || root.getPath() == null) {
            throw new IllegalArgumentException("Unsupported Krkr game root URI");
        }
        return root.getPath();
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
        if (!requested.isDirectory() && !requested.mkdirs()) {
            throw new IOException("Unable to create private save directory");
        }
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

    private void finishWithResult(EngineResult result, Integer diagnosticCode) {
        Intent data = EngineContract.resultData(result);
        if (diagnosticCode != null) {
            data.putExtra(EXTRA_RESULT_CODE, diagnosticCode);
        }
        setResult(result == EngineResult.NORMAL_EXIT ? RESULT_OK : RESULT_CANCELED, data);
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
