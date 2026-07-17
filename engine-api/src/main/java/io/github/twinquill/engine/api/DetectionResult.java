/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.api;

import java.util.List;
import java.util.Objects;

/** Immutable result produced by {@link GameDetector}. */
public final class DetectionResult {
    private final EngineType candidate;
    private final double confidence;
    private final List<String> evidence;
    private final String conflictReason;

    public DetectionResult(
        EngineType candidate,
        double confidence,
        List<String> evidence,
        String conflictReason
    ) {
        this.candidate = Objects.requireNonNull(candidate);
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.evidence = List.copyOf(evidence);
        this.conflictReason = conflictReason;
    }

    public EngineType candidate() {
        return candidate;
    }

    public double confidence() {
        return confidence;
    }

    public List<String> evidence() {
        return evidence;
    }

    public String conflictReason() {
        return conflictReason;
    }

    public boolean requiresManualSelection() {
        return candidate == EngineType.AUTO;
    }
}
