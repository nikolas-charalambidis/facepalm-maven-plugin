/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Classifies findings into actionable risk levels.
 */
@Getter
@RequiredArgsConstructor
public enum Severity {

    /**
     * Low-risk detections or informational insights.
     */
    INFO("⚪"),

    /**
     * Moderate-risk findings requiring review.
     */
    WARNING("🟡"),

    /**
     * Critical findings that typically trigger build failure.
     */
    ERROR("🔴"),

    /**
     * Finding marked with an inline {@code facepalm:ignore} comment (#12). Listed in the reports for audits, but never
     * fails the build, whatever its score.
     */
    SUPPRESSED("⚫");

    private final String icon;
}
