/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only provider that issues a persistable grant from the fixture APK UID. */
public final class LauncherGrantBrokerProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.twinquill.test.grants";
    static final String METHOD_GRANT = "grant";
    static final String METHOD_REVOKE = "revoke";
    static final String METHOD_RESET_ARCHIVE_OPEN_COUNT =
        "reset-archive-open-count";
    static final String METHOD_ARCHIVE_OPEN_COUNT = "archive-open-count";
    static final String METHOD_RESET_AUDIO_OPEN_COUNT = "reset-audio-open-count";
    static final String METHOD_AUDIO_OPEN_COUNT = "audio-open-count";
    static final String METHOD_RESET_VIDEO_OPEN_COUNT = "reset-video-open-count";
    static final String METHOD_VIDEO_OPEN_COUNT = "video-open-count";
    static final String METHOD_RESET_VISUAL_OPEN_COUNT = "reset-visual-open-count";
    static final String METHOD_VISUAL_OPEN_COUNT = "visual-open-count";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (METHOD_RESET_VISUAL_OPEN_COUNT.equals(method)) {
            LauncherFixtureDocumentsProvider.resetVisualImageOpenCount();
            return Bundle.EMPTY;
        }
        if (METHOD_VISUAL_OPEN_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt("count", LauncherFixtureDocumentsProvider.visualImageOpenCount());
            return result;
        }
        if (METHOD_RESET_ARCHIVE_OPEN_COUNT.equals(method)) {
            LauncherFixtureDocumentsProvider.resetArchiveOpenCount(argument);
            return Bundle.EMPTY;
        }
        if (METHOD_ARCHIVE_OPEN_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt(
                "count",
                LauncherFixtureDocumentsProvider.archiveOpenCount(argument)
            );
            return result;
        }
        if (METHOD_RESET_AUDIO_OPEN_COUNT.equals(method)) {
            LauncherFixtureDocumentsProvider.resetAudioOpenCount();
            return Bundle.EMPTY;
        }
        if (METHOD_AUDIO_OPEN_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt(
                "count",
                LauncherFixtureDocumentsProvider.audioOpenCount()
            );
            return result;
        }
        if (METHOD_RESET_VIDEO_OPEN_COUNT.equals(method)) {
            LauncherFixtureDocumentsProvider.resetVideoOpenCount();
            return Bundle.EMPTY;
        }
        if (METHOD_VIDEO_OPEN_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt(
                "count",
                LauncherFixtureDocumentsProvider.videoOpenCount()
            );
            return result;
        }
        if (METHOD_REVOKE.equals(method)) {
            if (argument == null) {
                throw new IllegalArgumentException("A fixture root is required");
            }
            Uri treeUri = fixtureTree(argument);
            getContext().revokeUriPermission(
                "io.github.twinquill",
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
            return Bundle.EMPTY;
        }
        if (!METHOD_GRANT.equals(method)) {
            throw new IllegalArgumentException("Unknown fixture method");
        }
        int flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        Uri treeUri = argument == null
            ? LauncherFixtureDocumentsProvider.treeUri()
            : fixtureTree(argument);
        getContext().grantUriPermission("io.github.twinquill", treeUri, flags);
        Bundle result = new Bundle();
        result.putParcelable("uri", treeUri);
        result.putInt("flags", flags);
        return result;
    }

    private static Uri fixtureTree(String argument) {
        return argument.startsWith("m3-") ? KrkrM3FixtureDocumentsProvider.treeUri(argument)
            : LauncherFixtureDocumentsProvider.treeUri(argument);
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        throw new UnsupportedOperationException();
    }
}
