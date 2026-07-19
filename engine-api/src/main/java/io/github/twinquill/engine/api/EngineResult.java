/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

/** Result categories returned by an isolated engine process. */
public enum EngineResult {
    NORMAL_EXIT(0),
    USER_EXIT(1),
    PERMISSION_REVOKED(10),
    MISSING_PLUGIN(11),
    SCRIPT_ERROR(12),
    NATIVE_CRASH(13),
    INVALID_REQUEST(14),
    VFS_UNAVAILABLE(15);

    private final int code;

    EngineResult(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static EngineResult fromCode(int code) {
        for (EngineResult value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return NATIVE_CRASH;
    }
}
