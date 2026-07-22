/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.nativevfs.NativeVfs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** M0 Krkr source-build entry point hosted in the app-private {@code :krkr} process. */
public final class KrkrEngineActivity extends Activity {
    public static final String EXTRA_GAME_ROOT =
        "io.github.twinquill.extra.KRKR_GAME_ROOT";
    public static final String EXTRA_RESULT_CODE =
        "io.github.twinquill.extra.KRKR_RESULT_CODE";

    private static final String LOG_TAG = "TwinQuill/KrkrActivity";
    private static final long VFS_OK = 0;
    private static final long VFS_NOT_FOUND = -3;
    private static final int LOOSE_OPEN_FAILURE = 11;
    private static final int XP3_OPEN_FAILURE = 30;

    private static native int nativeRunLooseStartup(String startupPath);
    private static native int nativeRunLooseStartupDescriptor(int descriptor);
    private static native int nativeRunXp3Startup(String archivePath);
    private static native int nativeRunXp3StartupDescriptor(int descriptor);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LaunchTarget target;
        try {
            target = resolveLaunchTarget(getIntent());
            System.loadLibrary("twinquill_native_vfs");
            System.loadLibrary("twinquill_engine_krkr");
        } catch (SafVfsUnavailableException exception) {
            Log.e(LOG_TAG, "Unable to access Krkr SAF game root", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE, null);
            return;
        } catch (IOException | IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Invalid Krkr game root", exception);
            finishWithResult(EngineResult.INVALID_REQUEST, null);
            return;
        } catch (RuntimeException | LinkageError exception) {
            Log.e(LOG_TAG, "Unable to initialize the Krkr VFS", exception);
            finishWithResult(EngineResult.VFS_UNAVAILABLE, null);
            return;
        }

        Thread runner = new Thread(() -> {
            int result;
            try {
                result = runLaunchTarget(target);
            } catch (RuntimeException | LinkageError exception) {
                Log.e(LOG_TAG, "Krkr startup runner failed", exception);
                result = target.xp3 ? XP3_OPEN_FAILURE : LOOSE_OPEN_FAILURE;
            }
            Log.i(LOG_TAG, (target.xp3 ? "XP3" : "Loose")
                + " startup completed with result " + result);
            EngineResult category =
                result == 0 ? EngineResult.NORMAL_EXIT : EngineResult.SCRIPT_ERROR;
            final int diagnostic = result;
            runOnUiThread(() -> finishWithResult(category, diagnostic));
        }, "TwinQuill-Krkr-M0");
        runner.start();
    }

    private LaunchTarget resolveLaunchTarget(Intent intent) throws IOException {
        EngineLaunchRequest request = EngineContract.launchRequest(intent);
        if (request == null) {
            if (intent == null) {
                throw new IllegalArgumentException("Krkr launch intent is required");
            }
            String legacyRoot = intent.getStringExtra(EXTRA_GAME_ROOT);
            if (legacyRoot == null || legacyRoot.isBlank()) {
                throw new IllegalArgumentException("Missing Krkr game root");
            }
            return resolveLocalStartup(legacyRoot);
        }
        if (request.engineType() != EngineType.KRKR) {
            throw new IllegalArgumentException("Krkr received a non-Krkr request");
        }

        NativeVfs.install(this);
        requirePrivateSaveDirectory(request);
        Uri root = request.gameRootUri();
        if ("content".equals(root.getScheme())) {
            return resolveSafStartup(root);
        }
        if (!"file".equals(root.getScheme()) || root.getPath() == null) {
            throw new IllegalArgumentException("Unsupported Krkr game root URI");
        }
        return resolveLocalStartup(root.getPath());
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

    private static LaunchTarget resolveLocalStartup(String gameRoot) throws IOException {
        if (gameRoot == null || gameRoot.isBlank()) {
            throw new IllegalArgumentException("Missing game root");
        }
        File root = new File(gameRoot).getCanonicalFile();
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("Game root is not a directory");
        }
        File startup = new File(root, "startup.tjs").getCanonicalFile();
        if (root.equals(startup.getParentFile()) && startup.isFile()) {
            return LaunchTarget.localFile(startup, false);
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
        return LaunchTarget.localFile(archive, true);
    }

    private static LaunchTarget resolveSafStartup(Uri root) throws IOException {
        String[] listed = NativeVfs.list(root, "");
        if (listed == null) {
            throw new SafVfsUnavailableException("Unable to list Krkr SAF game root");
        }
        List<String> names = validatedRootNames(listed);
        String startup = uniqueCaseInsensitiveName(names, "startup.tjs");
        if (startup != null) {
            requireSafRegularFile(root, startup);
            return LaunchTarget.contentFile(root, startup, false);
        }

        List<String> archives = new ArrayList<>();
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".xp3")) {
                requireSafRegularFile(root, name);
                archives.add(name);
            }
        }
        if (archives.isEmpty()) {
            throw new IllegalArgumentException("Missing root startup.tjs or XP3 archive");
        }
        rejectCaseInsensitiveDuplicates(archives);
        archives.sort(String.CASE_INSENSITIVE_ORDER);
        return LaunchTarget.contentFile(root, archives.get(0), true);
    }

    private static List<String> validatedRootNames(String[] names) {
        List<String> result = new ArrayList<>(names.length);
        Set<String> exactNames = new HashSet<>();
        for (String name : names) {
            requireSafeRootName(name);
            if (!exactNames.add(name)) {
                throw new IllegalArgumentException("Ambiguous duplicate SAF root entry");
            }
            result.add(name);
        }
        return result;
    }

    private static String uniqueCaseInsensitiveName(List<String> names, String expected) {
        String match = null;
        for (String name : names) {
            if (expected.equalsIgnoreCase(name)) {
                if (match != null) {
                    throw new IllegalArgumentException("Ambiguous SAF root startup.tjs");
                }
                match = name;
            }
        }
        return match;
    }

    private static void rejectCaseInsensitiveDuplicates(List<String> names) {
        Set<String> folded = new HashSet<>();
        for (String name : names) {
            if (!folded.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Ambiguous SAF root XP3 archive");
            }
        }
    }

    private static void requireSafeRootName(String name) {
        if (name == null || name.isBlank()
            || ".".equals(name) || "..".equals(name)
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SAF root entry name");
        }
    }

    private static void requireSafRegularFile(Uri root, String relativePath) throws IOException {
        long[] stat = NativeVfs.stat(root, relativePath);
        if (stat == null || stat.length != 6) {
            throw new SafVfsUnavailableException("Unable to stat SAF root entry");
        }
        if (stat[0] == VFS_NOT_FOUND || stat[1] == 0) {
            throw new IllegalArgumentException("SAF root entry disappeared");
        }
        if (stat[0] != VFS_OK) {
            throw new SafVfsUnavailableException("Unable to inspect SAF root entry");
        }
        if (stat[2] != 0) {
            throw new IllegalArgumentException("SAF root entry is a directory");
        }
    }

    private static int runLaunchTarget(LaunchTarget target) {
        if (target.contentRoot != null) {
            return runContentStartup(target);
        }
        return target.xp3
            ? nativeRunXp3Startup(target.path)
            : nativeRunLooseStartup(target.path);
    }

    private static int runContentStartup(LaunchTarget target) {
        int descriptor = NativeVfs.openReadOnlyDescriptor(target.contentRoot, target.relativePath);
        if (descriptor < 0) {
            Log.e(LOG_TAG, "Unable to open Krkr SAF entry " + target.relativePath
                + " as a seekable descriptor: " + descriptor);
            return target.xp3 ? XP3_OPEN_FAILURE : LOOSE_OPEN_FAILURE;
        }
        try {
            int result = target.xp3
                ? nativeRunXp3StartupDescriptor(descriptor)
                : nativeRunLooseStartupDescriptor(descriptor);
            descriptor = -1;
            return result;
        } finally {
            if (descriptor >= 0) {
                closeDescriptor(descriptor);
            }
        }
    }

    private static void closeDescriptor(int descriptor) {
        try {
            ParcelFileDescriptor.adoptFd(descriptor).close();
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Unable to close Krkr SAF descriptor after failed handoff", exception);
        }
    }

    private void finishWithResult(EngineResult result, Integer diagnosticCode) {
        Intent data = EngineContract.resultData(result);
        if (diagnosticCode != null) {
            data.putExtra(EXTRA_RESULT_CODE, diagnosticCode);
        }
        setResult(result == EngineResult.NORMAL_EXIT ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }

    private static final class SafVfsUnavailableException extends IOException {
        SafVfsUnavailableException(String message) {
            super(message);
        }
    }

    private static final class LaunchTarget {
        final String path;
        final Uri contentRoot;
        final String relativePath;
        final boolean xp3;

        private LaunchTarget(String path, Uri contentRoot, String relativePath, boolean xp3) {
            this.path = path;
            this.contentRoot = contentRoot;
            this.relativePath = relativePath;
            this.xp3 = xp3;
        }

        static LaunchTarget localFile(File file, boolean xp3) {
            return new LaunchTarget(file.getAbsolutePath(), null, "", xp3);
        }

        static LaunchTarget contentFile(Uri root, String relativePath, boolean xp3) {
            return new LaunchTarget("", root, relativePath, xp3);
        }
    }
}
