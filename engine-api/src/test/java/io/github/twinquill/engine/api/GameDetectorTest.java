/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

public final class GameDetectorTest {
    @Test
    public void detectsOnsCaseInsensitively() throws Exception {
        DetectionResult result = GameDetector.detect(probe(List.of("0.TXT"), Set.of()));
        assertEquals(EngineType.ONS, result.candidate());
        assertEquals(1.0, result.confidence(), 0.0);
        assertFalse(result.requiresManualSelection());
    }

    @Test
    public void detectsLooseKirikiriStartup() throws Exception {
        DetectionResult result =
            GameDetector.detect(probe(List.of("startup.tjs"), Set.of()));
        assertEquals(EngineType.KRKR, result.candidate());
        assertEquals(List.of("root:startup.tjs"), result.evidence());
    }

    @Test
    public void detectsSupportedXp3Startup() throws Exception {
        DetectionResult result =
            GameDetector.detect(probe(List.of("data.xp3"), Set.of("data.xp3")));
        assertEquals(EngineType.KRKR, result.candidate());
        assertEquals(0.9, result.confidence(), 0.0);
    }

    @Test
    public void requiresManualSelectionForConflict() throws Exception {
        DetectionResult result = GameDetector.detect(
            probe(List.of("nscript.dat", "startup.tjs"), Set.of())
        );
        assertEquals(EngineType.AUTO, result.candidate());
        assertTrue(result.requiresManualSelection());
        assertTrue(result.conflictReason().contains("Both"));
    }

    @Test
    public void requiresManualSelectionForUnknownDirectory() throws Exception {
        DetectionResult result =
            GameDetector.detect(probe(List.of("readme.txt"), Set.of()));
        assertEquals(EngineType.AUTO, result.candidate());
        assertTrue(result.evidence().isEmpty());
    }

    private static GameDirectoryProbe probe(List<String> names, Set<String> xp3WithStartup) {
        return new GameDirectoryProbe() {
            @Override
            public List<String> rootFileNames() {
                return names;
            }

            @Override
            public boolean archiveContainsRootStartup(String archiveName) {
                return xp3WithStartup.contains(archiveName);
            }
        };
    }
}
