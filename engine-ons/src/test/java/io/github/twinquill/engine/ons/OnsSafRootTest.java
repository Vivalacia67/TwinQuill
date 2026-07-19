/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class OnsSafRootTest {
    @Test
    public void mapsSyntheticAndRelativePaths() {
        String root = OnsSafRoot.create("game-1");

        assertEquals("0.txt", OnsSafRoot.relativePath(root, root + "/0.txt"));
        assertEquals(
            "voice/chapter01.ogg",
            OnsSafRoot.relativePath(root, root + "\\voice\\chapter01.ogg")
        );
        assertEquals("default.ttf", OnsSafRoot.relativePath(root, "default.ttf"));
    }

    @Test
    public void rejectsAbsolutePathsOutsideSyntheticRoot() {
        String root = OnsSafRoot.create("game-1");

        assertNull(OnsSafRoot.relativePath(root, "/data/user/0/save.dat"));
        assertNull(OnsSafRoot.relativePath(root, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeGameId() {
        OnsSafRoot.create("../outside");
    }
}
