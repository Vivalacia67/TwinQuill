/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

import io.github.twinquill.engine.api.DetectionResult;
import io.github.twinquill.engine.api.EngineType;

/** Persistent launcher entry. Game content remains outside the database. */
@Entity(
    tableName = "games",
    indices = {@Index(value = {"directoryUri"}, unique = true)}
)
public final class GameEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String name;

    @NonNull
    public String directoryUri;

    @NonNull
    public String detectedEngine;

    @NonNull
    public String overrideEngine;

    public double detectionConfidence;

    @NonNull
    public String detectionEvidence;

    @Nullable
    public String detectionConflict;

    @Nullable
    public String coverUri;

    public long createdAt;

    @Nullable
    public Long lastPlayedAt;

    @NonNull
    public String configJson;

    public boolean permissionValid;

    public GameEntity(
        @NonNull String id,
        @NonNull String name,
        @NonNull String directoryUri,
        @NonNull String detectedEngine,
        @NonNull String overrideEngine,
        double detectionConfidence,
        @NonNull String detectionEvidence,
        @Nullable String detectionConflict,
        @Nullable String coverUri,
        long createdAt,
        @Nullable Long lastPlayedAt,
        @NonNull String configJson,
        boolean permissionValid
    ) {
        this.id = id;
        this.name = name;
        this.directoryUri = directoryUri;
        this.detectedEngine = detectedEngine;
        this.overrideEngine = overrideEngine;
        this.detectionConfidence = detectionConfidence;
        this.detectionEvidence = detectionEvidence;
        this.detectionConflict = detectionConflict;
        this.coverUri = coverUri;
        this.createdAt = createdAt;
        this.lastPlayedAt = lastPlayedAt;
        this.configJson = configJson;
        this.permissionValid = permissionValid;
    }

    public static GameEntity create(
        String name,
        String directoryUri,
        DetectionResult detection
    ) {
        return new GameEntity(
            UUID.randomUUID().toString(),
            name,
            directoryUri,
            detection.candidate().name(),
            EngineType.AUTO.name(),
            detection.confidence(),
            String.join("\n", detection.evidence()),
            detection.conflictReason(),
            null,
            System.currentTimeMillis(),
            null,
            "{}",
            true
        );
    }

    public EngineType effectiveEngine() {
        EngineType override = EngineType.fromStorage(overrideEngine);
        return override == EngineType.AUTO
            ? EngineType.fromStorage(detectedEngine)
            : override;
    }
}
