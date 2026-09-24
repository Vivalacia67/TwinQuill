/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** Parcelable launch request crossing the launcher/engine process boundary. */
public final class EngineLaunchRequest implements Parcelable {
    private final String gameId;
    private final Uri gameRootUri;
    private final String saveDirectoryPath;
    private final EngineType engineType;
    private final Bundle arguments;

    public EngineLaunchRequest(
        String gameId,
        Uri gameRootUri,
        String saveDirectoryPath,
        EngineType engineType,
        Bundle arguments
    ) {
        this.gameId = requireGameId(gameId);
        this.gameRootUri = Objects.requireNonNull(gameRootUri, "gameRootUri");
        this.saveDirectoryPath = requireText(saveDirectoryPath, "saveDirectoryPath");
        this.engineType = Objects.requireNonNull(engineType, "engineType");
        if (engineType == EngineType.AUTO) {
            throw new IllegalArgumentException("A launch request must name a concrete engine");
        }
        this.arguments = arguments == null ? Bundle.EMPTY : new Bundle(arguments);
    }

    private EngineLaunchRequest(Parcel source) {
        gameId = requireGameId(source.readString());
        gameRootUri = Objects.requireNonNull(
            source.readParcelable(Uri.class.getClassLoader())
        );
        saveDirectoryPath = Objects.requireNonNull(source.readString());
        engineType = EngineType.valueOf(Objects.requireNonNull(source.readString()));
        Bundle readArguments = source.readBundle(Bundle.class.getClassLoader());
        arguments = readArguments == null ? Bundle.EMPTY : readArguments;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireGameId(String value) {
        String gameId = requireText(value, "gameId");
        if (!gameId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("gameId is not a safe path segment");
        }
        return gameId;
    }

    public String gameId() {
        return gameId;
    }

    public Uri gameRootUri() {
        return gameRootUri;
    }

    public String saveDirectoryPath() {
        return saveDirectoryPath;
    }

    public EngineType engineType() {
        return engineType;
    }

    public Bundle arguments() {
        return new Bundle(arguments);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(gameId);
        destination.writeParcelable(gameRootUri, flags);
        destination.writeString(saveDirectoryPath);
        destination.writeString(engineType.name());
        destination.writeBundle(arguments);
    }

    public static final Creator<EngineLaunchRequest> CREATOR =
        new Creator<>() {
            @Override
            public EngineLaunchRequest createFromParcel(Parcel source) {
                return new EngineLaunchRequest(source);
            }

            @Override
            public EngineLaunchRequest[] newArray(int size) {
                return new EngineLaunchRequest[size];
            }
        };
}
