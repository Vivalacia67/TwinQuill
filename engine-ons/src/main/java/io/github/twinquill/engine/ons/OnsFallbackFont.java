/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Installs the audited CJK fallback font as a normal app-private file. */
final class OnsFallbackFont {
    static final String PRIVATE_FILE_NAME = "NotoSansCJKsc-Regular-2.004.otf";
    static final long EXPECTED_LENGTH = 16_437_364L;

    private static final String ASSET_FILE_NAME = "NotoSansCJKsc-Regular.otf";
    private static final String EXPECTED_SHA256 =
        "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b";
    private static final Object INSTALL_LOCK = new Object();

    private OnsFallbackFont() {
    }

    static File prepare(Context context) {
        synchronized (INSTALL_LOCK) {
            File directory = new File(context.getFilesDir(), "engine-assets/ons");
            File target = new File(directory, PRIVATE_FILE_NAME);
            if (target.isFile() && target.length() == EXPECTED_LENGTH) {
                return canonical(target);
            }
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Unable to create ONS asset directory");
            }

            File temporary = null;
            try {
                temporary = File.createTempFile("fallback-font-", ".tmp", directory);
                copyAndVerify(context.getAssets(), temporary);
                moveIntoPlace(temporary, target);
                return canonical(target);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to install ONS fallback font", exception);
            } finally {
                if (temporary != null && temporary.exists()) {
                    temporary.delete();
                }
            }
        }
    }

    private static void copyAndVerify(AssetManager assets, File destination)
        throws IOException {
        MessageDigest digest = sha256Digest();
        long copied = 0;
        try (
            InputStream input = assets.open(
                ASSET_FILE_NAME,
                AssetManager.ACCESS_STREAMING
            );
            FileOutputStream output = new FileOutputStream(destination)
        ) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;
            }
            output.getFD().sync();
        }
        if (copied != EXPECTED_LENGTH
            || !EXPECTED_SHA256.equals(hex(digest.digest()))) {
            throw new IOException("Packaged ONS fallback font failed verification");
        }
    }

    private static void moveIntoPlace(File source, File target) throws IOException {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve ONS fallback font", exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Android must provide SHA-256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
