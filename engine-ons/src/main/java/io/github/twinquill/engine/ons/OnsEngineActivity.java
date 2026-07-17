/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

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

/**
 * ONS protocol entry point hosted in the app-private {@code :ons} process.
 *
 * <p>The SDL runtime is a separate non-exported Activity so invalid or
 * not-yet-supported SAF requests can return without initializing SDL.</p>
 */
public final class OnsEngineActivity extends Activity {
    public static final String EXTRA_GAME_ID =
        "io.github.twinquill.extra.ONS_GAME_ID";
    public static final String EXTRA_GAME_ROOT =
        "io.github.twinquill.extra.ONS_GAME_ROOT";
    public static final String EXTRA_SAVE_ROOT =
        "io.github.twinquill.extra.ONS_SAVE_ROOT";
    public static final String EXTRA_FONT_PATH =
        "io.github.twinquill.extra.ONS_FONT_PATH";
    public static final String EXTRA_ENCODING =
        "io.github.twinquill.extra.ONS_ENCODING";

    private static final int REQUEST_RUNTIME = 1;
    private static final String LOG_TAG = "TwinQuill/OnsEntry";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent runtimeIntent;
        try {
            runtimeIntent = runtimeIntent(getIntent());
        } catch (IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid ONS launch request", exception);
            finishWithResult(EngineResult.INVALID_REQUEST);
            return;
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to initialize the ONS VFS", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE);
            return;
        }

        if (runtimeIntent == null) {
            finishWithResult(EngineResult.VFS_UNAVAILABLE);
            return;
        }
        try {
            startActivityForResult(runtimeIntent, REQUEST_RUNTIME);
        } catch (RuntimeException exception) {
            Log.e(LOG_TAG, "Unable to start the ONS runtime", exception);
            finishWithResult(EngineResult.INVALID_REQUEST);
        }
    }

    private Intent runtimeIntent(Intent source) {
        if (source == null) {
            throw new IllegalArgumentException("ONS launch intent is required");
        }

        Intent runtime = new Intent(this, OnsRuntimeActivity.class);
        EngineLaunchRequest request = EngineContract.launchRequest(source);
        if (request == null) {
            copyLegacyExtras(source, runtime);
            return runtime;
        }
        if (request.engineType() != EngineType.ONS) {
            throw new IllegalArgumentException("ONS received a non-ONS request");
        }

        NativeVfs.install(this);
        Uri root = request.gameRootUri();
        if ("content".equals(root.getScheme())) {
            Log.i(LOG_TAG, "ONS SAF runtime integration is scheduled for M2");
            return null;
        }
        if (!"file".equals(root.getScheme()) || root.getPath() == null) {
            throw new IllegalArgumentException("Unsupported ONS game root URI");
        }

        runtime.putExtra(EXTRA_GAME_ID, request.gameId());
        runtime.putExtra(EXTRA_GAME_ROOT, root.getPath());
        runtime.putExtra(EXTRA_SAVE_ROOT, request.saveDirectoryPath());
        Bundle arguments = request.arguments();
        copyTextArgument(arguments, runtime, EXTRA_FONT_PATH);
        copyTextArgument(arguments, runtime, EXTRA_ENCODING);
        return runtime;
    }

    private static void copyLegacyExtras(Intent source, Intent runtime) {
        String gameId = source.getStringExtra(EXTRA_GAME_ID);
        String gameRoot = source.getStringExtra(EXTRA_GAME_ROOT);
        if (gameId == null || gameId.isBlank()
            || gameRoot == null || gameRoot.isBlank()) {
            throw new IllegalArgumentException("Incomplete legacy ONS request");
        }
        runtime.putExtra(EXTRA_GAME_ID, gameId);
        runtime.putExtra(EXTRA_GAME_ROOT, gameRoot);
        runtime.putExtra(EXTRA_SAVE_ROOT, source.getStringExtra(EXTRA_SAVE_ROOT));
        runtime.putExtra(EXTRA_FONT_PATH, source.getStringExtra(EXTRA_FONT_PATH));
        runtime.putExtra(EXTRA_ENCODING, source.getStringExtra(EXTRA_ENCODING));
    }

    private static void copyTextArgument(Bundle source, Intent target, String key) {
        String value = source.getString(key);
        if (value != null) {
            target.putExtra(key, value);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_RUNTIME) {
            return;
        }
        EngineResult result = resultCode == RESULT_OK
            ? EngineResult.NORMAL_EXIT
            : EngineResult.NATIVE_CRASH;
        if (data != null && data.hasExtra(EngineContract.EXTRA_RESULT)) {
            result = EngineResult.fromCode(
                data.getIntExtra(
                    EngineContract.EXTRA_RESULT,
                    EngineResult.NATIVE_CRASH.code()
                )
            );
        }
        finishWithResult(result);
    }

    private void finishWithResult(EngineResult result) {
        setResult(
            result == EngineResult.NORMAL_EXIT ? RESULT_OK : RESULT_CANCELED,
            EngineContract.resultData(result)
        );
        finish();
    }
}
