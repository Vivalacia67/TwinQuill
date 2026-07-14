/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

/** ONS runtime entry point hosted in the app-private {@code :ons} process. */
public final class OnsEngineActivity extends ONScripter {
    public static final String EXTRA_GAME_ID =
        "io.github.twinquill.extra.ONS_GAME_ID";
    public static final String EXTRA_GAME_ROOT =
        "io.github.twinquill.extra.ONS_GAME_ROOT";
    public static final String EXTRA_FONT_PATH =
        "io.github.twinquill.extra.ONS_FONT_PATH";
    public static final String EXTRA_ENCODING =
        "io.github.twinquill.extra.ONS_ENCODING";
}
