/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Private, validated state passed from the Krkr broker to its runtime host.
 *
 * <p>This is deliberately not part of {@code EngineContract}: the launcher
 * only ever talks to {@link KrkrEngineActivity}, while this state is consumed
 * by the non-exported runtime activity in the isolated Krkr process.</p>
 */
final class KrkrRuntimeRequest {
    static final int SOURCE_SAF = 1;
    static final int SOURCE_LOOSE = 2;
    static final int SOURCE_XP3 = 3;

    private static final String EXTRA_PREFIX =
        "io.github.twinquill.engine.krkr.runtime.";
    static final String EXTRA_SOURCE_KIND = EXTRA_PREFIX + "SOURCE_KIND";
    static final String EXTRA_SOURCE = EXTRA_PREFIX + "SOURCE";
    static final String EXTRA_SAVE_DIRECTORY = EXTRA_PREFIX + "SAVE_DIRECTORY";
    static final String EXTRA_GAME_ID = EXTRA_PREFIX + "GAME_ID";
    static final String EXTRA_SCRIPT_HANDLE = EXTRA_PREFIX + "SCRIPT_HANDLE";

    private final int sourceKind;
    private final String source;
    private final String saveDirectory;
    private final String gameId;
    private long scriptHandle;

    private KrkrRuntimeRequest(
        int sourceKind,
        String source,
        String saveDirectory,
        String gameId
    ) {
        this.sourceKind = sourceKind;
        this.source = source;
        this.saveDirectory = saveDirectory;
        this.gameId = gameId;
    }

    static KrkrRuntimeRequest fromBroker(
        Context context,
        int sourceKind,
        String source,
        String saveDirectory,
        String gameId
    ) throws IOException {
        return validated(context, sourceKind, source, saveDirectory, gameId);
    }

    static KrkrRuntimeRequest fromIntent(Context context, Intent intent)
        throws IOException {
        if (intent == null) {
            throw new IllegalArgumentException("Krkr runtime intent is required");
        }
        int sourceKind = intent.getIntExtra(EXTRA_SOURCE_KIND, 0);
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String saveDirectory = intent.getStringExtra(EXTRA_SAVE_DIRECTORY);
        String gameId = intent.getStringExtra(EXTRA_GAME_ID);
        KrkrRuntimeRequest request = validated(context, sourceKind, source, saveDirectory, gameId);
        request.scriptHandle = intent.getLongExtra(EXTRA_SCRIPT_HANDLE, 0L);
        return request;
    }

    private static KrkrRuntimeRequest validated(
        Context context,
        int sourceKind,
        String source,
        String saveDirectory,
        String gameId
    ) throws IOException {
        Objects.requireNonNull(context, "context");
        if (sourceKind != SOURCE_SAF
            && sourceKind != SOURCE_LOOSE
            && sourceKind != SOURCE_XP3) {
            throw new IllegalArgumentException("Unknown Krkr runtime source kind");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Krkr runtime source is required");
        }
        String checkedSource;
        if (sourceKind == SOURCE_SAF) {
            Uri uri = Uri.parse(source);
            if (!"content".equals(uri.getScheme())
                || uri.getAuthority() == null
                || uri.getAuthority().isBlank()) {
                throw new IllegalArgumentException("Krkr SAF source must use content://");
            }
            checkedSource = uri.toString();
        } else {
            File canonicalSource = new File(source).getCanonicalFile();
            if (!canonicalSource.isFile()) {
                throw new IllegalArgumentException("Krkr runtime source is not a file");
            }
            if (sourceKind == SOURCE_LOOSE
                && !"startup.tjs".equalsIgnoreCase(canonicalSource.getName())) {
                throw new IllegalArgumentException("Krkr loose source must be startup.tjs");
            }
            if (sourceKind == SOURCE_XP3
                && !canonicalSource.getName().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".xp3")) {
                throw new IllegalArgumentException("Krkr XP3 source must end in .xp3");
            }
            checkedSource = canonicalSource.getPath();
        }

        if (gameId == null || !gameId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Krkr gameId is not a safe path segment");
        }
        File saveBase = new File(context.getFilesDir().getCanonicalFile(), "saves");
        if (!saveBase.getCanonicalFile().equals(saveBase)) {
            throw new IllegalArgumentException("Krkr save base must not be a symbolic link");
        }
        File expectedSave = new File(saveBase, gameId);
        File requestedSave = new File(
            saveDirectory == null ? "" : saveDirectory
        ).getCanonicalFile();
        if (!requestedSave.equals(expectedSave)) {
            throw new IllegalArgumentException(
                "Krkr save directory must match the game-private directory"
            );
        }
        if (!requestedSave.isDirectory() && !requestedSave.mkdirs()
            && !requestedSave.isDirectory()) {
            throw new IOException("Unable to create private Krkr save directory");
        }
        return new KrkrRuntimeRequest(
            sourceKind,
            checkedSource,
            requestedSave.getPath(),
            gameId
        );
    }

    void putInto(Intent intent) {
        intent.putExtra(EXTRA_SOURCE_KIND, sourceKind)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_SAVE_DIRECTORY, saveDirectory)
            .putExtra(EXTRA_GAME_ID, gameId)
            .putExtra(EXTRA_SCRIPT_HANDLE, scriptHandle);
    }

    int sourceKind() {
        return sourceKind;
    }

    KrkrRuntimeRequest withScriptSession(KrkrScriptSession session) {
        if (!session.matches(this)) throw new IllegalArgumentException("Script source mismatch");
        scriptHandle = session.handle();
        return this;
    }

    long scriptHandle() { return scriptHandle; }

    String source() {
        return source;
    }

    String saveDirectory() {
        return saveDirectory;
    }

    String gameId() {
        return gameId;
    }
}
