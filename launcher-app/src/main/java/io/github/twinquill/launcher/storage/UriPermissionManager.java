/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

/** Owns persisted read grants without requesting broad storage permissions. */
public final class UriPermissionManager {
    private UriPermissionManager() {
    }

    public static void takeReadPermission(
        ContentResolver resolver,
        Uri uri,
        int resultFlags
    ) {
        int flags = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (flags == 0) {
            throw new SecurityException("Directory picker did not grant read access");
        }
        resolver.takePersistableUriPermission(uri, flags);
    }

    public static boolean hasReadPermission(ContentResolver resolver, Uri uri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    public static void releaseReadPermission(ContentResolver resolver, Uri uri) {
        try {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // A revoked or already released grant is already in the desired state.
        }
    }
}
