/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Detects standard ONS and Kirikiri layouts without mutating the game directory. */
public final class GameDetector {
    private static final Set<String> ONS_MARKERS = Set.of(
        "0.txt",
        "00.txt",
        "nscr_sec.dat",
        "nscript.dat",
        "onscript.nt2",
        "onscript.nt3"
    );

    private GameDetector() {
    }

    public static DetectionResult detect(GameDirectoryProbe directory) throws IOException {
        Map<String, String> names = new HashMap<>();
        for (String name : directory.rootFileNames()) {
            if (name != null && !name.isBlank()) {
                names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }

        List<String> onsEvidence = new ArrayList<>();
        for (String marker : ONS_MARKERS) {
            if (names.containsKey(marker)) {
                onsEvidence.add("root:" + names.get(marker));
            }
        }

        List<String> krkrEvidence = new ArrayList<>();
        if (names.containsKey("startup.tjs")) {
            krkrEvidence.add("root:" + names.get("startup.tjs"));
        } else {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                if (entry.getKey().endsWith(".xp3")
                    && directory.archiveContainsRootStartup(entry.getValue())) {
                    krkrEvidence.add("xp3:" + entry.getValue() + "!/startup.tjs");
                    break;
                }
            }
        }

        if (!onsEvidence.isEmpty() && !krkrEvidence.isEmpty()) {
            List<String> evidence = new ArrayList<>(onsEvidence);
            evidence.addAll(krkrEvidence);
            return new DetectionResult(
                EngineType.AUTO,
                0.0,
                evidence,
                "Both ONS and Kirikiri markers were found"
            );
        }
        if (!onsEvidence.isEmpty()) {
            return new DetectionResult(EngineType.ONS, 1.0, onsEvidence, null);
        }
        if (!krkrEvidence.isEmpty()) {
            double confidence = krkrEvidence.get(0).startsWith("root:") ? 1.0 : 0.9;
            return new DetectionResult(EngineType.KRKR, confidence, krkrEvidence, null);
        }
        return new DetectionResult(
            EngineType.AUTO,
            0.0,
            List.of(),
            "No supported ONS or Kirikiri startup marker was found"
        );
    }
}
