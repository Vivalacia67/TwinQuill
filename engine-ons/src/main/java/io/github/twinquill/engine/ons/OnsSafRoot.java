/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

/** Maps ONS's synthetic POSIX root back to a path relative to a SAF tree. */
final class OnsSafRoot {
    private static final String BASE = "/twinquill-saf/";

    private OnsSafRoot() {
    }

    static String create(String gameId) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid ONS game ID");
        }
        return BASE + gameId;
    }

    static String relativePath(String syntheticRoot, String requestedPath) {
        if (syntheticRoot == null || requestedPath == null) {
            return null;
        }
        String root = normalize(syntheticRoot);
        String requested = normalize(requestedPath);
        String prefix = root + "/";
        if (requested.startsWith(prefix)) {
            return requested.substring(prefix.length());
        }
        if (!requested.startsWith("/")) {
            return requested;
        }
        return null;
    }

    private static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
