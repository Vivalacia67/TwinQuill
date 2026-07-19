/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import java.io.IOException;
import java.util.List;

/** Minimal directory view used by the platform-independent game detector. */
public interface GameDirectoryProbe {
    List<String> rootFileNames() throws IOException;

    /**
     * Returns whether an archive contains a supported root {@code startup.tjs}.
     * Implementations must return false for protected or unreadable archives.
     */
    boolean archiveContainsRootStartup(String archiveName) throws IOException;
}
