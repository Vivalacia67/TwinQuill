/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

/** Stable Intent keys shared by the launcher and isolated engine processes. */
public final class EngineContract {
    public static final String EXTRA_LAUNCH_REQUEST =
        "io.github.twinquill.extra.ENGINE_LAUNCH_REQUEST";
    public static final String EXTRA_RESULT =
        "io.github.twinquill.extra.ENGINE_RESULT";

    private EngineContract() {
    }
}
