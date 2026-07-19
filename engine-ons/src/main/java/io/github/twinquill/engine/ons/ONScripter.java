/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.twinquill.nativevfs.NativeVfs;

/**
 * JNI bridge required by ONScripterYuri's source-level Android entry points.
 *
 * <p>The native project exports callbacks for a class named {@code ONScripter}.
 * Product code launches {@link OnsEngineActivity}; keeping this bridge as its
 * base class lets the public Activity retain the architecture's explicit name
 * without modifying the pinned upstream source snapshot.</p>
 */
public abstract class ONScripter extends SDLActivity {
    private static final String TAG = "TwinQuill/ONS";

    private String[] onsArguments = new String[0];
    private Uri safTreeUri;
    private String safRoot;

    private native int nativeInitJavaCallbacks();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        onsArguments = createArguments(getIntent());
        if (safTreeUri != null) {
            NativeVfs.install(this);
        }
        super.onCreate(savedInstanceState);
        nativeInitJavaCallbacks();
        enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL2",
            "SDL2_image",
            "SDL2_mixer",
            "SDL2_ttf",
            "twinquill_ons_lua",
            "twinquill_ons_bz2",
            "twinquill_engine_ons"
        };
    }

    @Override
    protected String[] getArguments() {
        return onsArguments.clone();
    }

    /** Native fallback for paths that regular POSIX I/O could not open. */
    @SuppressWarnings("unused")
    public int getFD(byte[] pathBytes, int mode) {
        if (mode != 0 || safTreeUri == null) {
            return -1;
        }
        String requestedPath = new String(pathBytes, StandardCharsets.UTF_8);
        String relativePath = OnsSafRoot.relativePath(safRoot, requestedPath);
        if (relativePath == null || relativePath.isBlank()) {
            return -1;
        }
        int descriptor = NativeVfs.openReadOnlyDescriptor(safTreeUri, relativePath);
        if (descriptor < 0) {
            Log.d(TAG, "SAF read unavailable (" + descriptor + "): " + relativePath);
            return -1;
        }
        return descriptor;
    }

    /** Native fallback for directories that regular POSIX I/O could not make. */
    @SuppressWarnings("unused")
    public int mkdir(byte[] pathBytes) {
        return -1;
    }

    /** Callback used by the upstream engine for platform video playback. */
    @SuppressWarnings("unused")
    public void playVideo(byte[] pathBytes) {
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        Log.w(TAG, "External video playback is disabled in the M0 prototype: " + path);
    }

    private String[] createArguments(Intent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("ONS launch intent is required");
        }

        String gameId = requireSafeGameId(intent.getStringExtra(OnsEngineActivity.EXTRA_GAME_ID));
        String gameRootUri = intent.getStringExtra(OnsEngineActivity.EXTRA_GAME_ROOT_URI);
        String gameRootArgument;
        if (gameRootUri == null) {
            File gameRoot =
                requireDirectory(intent.getStringExtra(OnsEngineActivity.EXTRA_GAME_ROOT));
            gameRootArgument = gameRoot.getAbsolutePath();
        } else {
            Uri parsedRoot = Uri.parse(gameRootUri);
            if (!"content".equals(parsedRoot.getScheme())
                || !DocumentsContract.isTreeUri(parsedRoot)) {
                throw new IllegalArgumentException("ONS game root is not a SAF tree");
            }
            safTreeUri = parsedRoot;
            safRoot = OnsSafRoot.create(gameId);
            gameRootArgument = safRoot;
        }
        File saveRoot = requirePrivateSaveDirectory(
            intent.getStringExtra(OnsEngineActivity.EXTRA_SAVE_ROOT),
            gameId
        );

        List<String> arguments = new ArrayList<>();
        arguments.add("--root");
        arguments.add(gameRootArgument);
        arguments.add("--save-dir");
        arguments.add(saveRoot.getAbsolutePath());
        arguments.add("--no-video");

        String fontPath = intent.getStringExtra(OnsEngineActivity.EXTRA_FONT_PATH);
        if (fontPath != null && !fontPath.isBlank()) {
            File font = requireFile(fontPath);
            arguments.add("--font");
            arguments.add(font.getAbsolutePath());
        }

        String encoding = intent.getStringExtra(OnsEngineActivity.EXTRA_ENCODING);
        if (encoding != null) {
            switch (encoding.toLowerCase(Locale.ROOT)) {
                case "gbk":
                case "sjis":
                case "utf8":
                    arguments.add("--enc:" + encoding.toLowerCase(Locale.ROOT));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported ONS encoding: " + encoding);
            }
        }

        return arguments.toArray(new String[0]);
    }

    private static File requireDirectory(String path) {
        File directory = canonicalFile(path, "game root");
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("ONS game root is not a directory");
        }
        return directory;
    }

    private static File requireFile(String path) {
        File file = canonicalFile(path, "font");
        if (!file.isFile()) {
            throw new IllegalArgumentException("ONS font path is not a file");
        }
        return file;
    }

    private static File canonicalFile(String path, String label) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("ONS " + label + " path is required");
        }
        try {
            return new File(path).getCanonicalFile();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid ONS " + label + " path", exception);
        }
    }

    private static String requireSafeGameId(String gameId) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid ONS game ID");
        }
        return gameId;
    }

    private File requirePrivateSaveDirectory(String requestedPath, String gameId) {
        try {
            File saveBase = new File(getFilesDir(), "saves").getCanonicalFile();
            File expected = new File(saveBase, gameId).getCanonicalFile();
            File requested = requestedPath == null || requestedPath.isBlank()
                ? expected
                : new File(requestedPath).getCanonicalFile();
            if (!requested.equals(expected)) {
                throw new IllegalArgumentException(
                    "ONS save directory must match the game-private directory"
                );
            }
            if (!requested.isDirectory() && !requested.mkdirs()) {
                throw new IllegalStateException("Unable to create private save directory");
            }
            return requested;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid ONS save directory", exception);
        }
    }

    private void enterImmersiveMode() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
