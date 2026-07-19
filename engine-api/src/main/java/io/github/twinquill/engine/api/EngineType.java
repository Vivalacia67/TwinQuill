/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

/** Engine selected for a game, or {@link #AUTO} when detection should decide. */
public enum EngineType {
    AUTO,
    ONS,
    KRKR;

    public static EngineType fromStorage(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
