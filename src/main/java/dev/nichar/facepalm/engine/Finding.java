/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.config.ScoringConfig;
import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Represents a discovered secret and its associated threat metadata.
 * Tracks risk, confidence, and scoring history throughout the evaluation pipeline.
 */
@Data
@Builder
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Finding {

    private final String patternName;

    /**
     * Unique hash for file-level deduplication.
     */
    @EqualsAndHashCode.Include
    private final String deduplicationHash;

    private final String secretValue;

    private final int lineNumber;

    private final FileContext context;

    private final String contextSnippet;

    private int riskScore;

    private int confidenceScore;

    /**
     * Historical log of scoring adjustments.
     */
    @Builder.Default
    private final List<String> scoreHistory = new ArrayList<>();

    /**
     * Updates threat scores and records the rule responsible for the adjustment.
     */
    public void log(@Nonnull final String rule, final int rDelta, final int cDelta) {
        riskScore = Math.max(0, Math.min(100, riskScore + rDelta));
        confidenceScore = Math.max(0, Math.min(100, confidenceScore + cDelta));
        scoreHistory.add(String.format(Locale.ROOT, "%s (%+d/%+d)", rule, rDelta, cDelta));
    }

    /**
     * Resolves the finding's severity based on configured threat thresholds.
     */
    @Nonnull
    public Severity getSeverity(@Nonnull final ScoringConfig config) {
        final double score = getNumericScore(config);
        if (score >= config.getErrorThreshold()) {
            return Severity.ERROR;
        }
        if (score >= config.getWarningThreshold()) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }

    /**
     * Calculates the composite threat score using the active scoring strategy.
     */
    public double getNumericScore(@Nonnull final ScoringConfig config) {
        return config.getStrategy().calculate(riskScore, confidenceScore);
    }

    /**
     * Returns a masked version of the secret for safe logging and reporting.
     * At most a quarter of the secret is revealed (up to 4 characters at each end); secrets shorter than 8
     * characters are fully masked.
     */
    @Nonnull
    public String getMaskedSecret() {
        final int reveal = Math.min(4, secretValue.length() / 8);
        return reveal == 0 ? "****"
            : secretValue.substring(0, reveal) + "..." + secretValue.substring(secretValue.length() - reveal);
    }

    /**
     * Returns the context snippet with every occurrence of the secret replaced by its masked form.
     * The raw snippet must never leave the process (logs, JSON, HTML, SARIF).
     */
    @Nonnull
    public String getMaskedSnippet() {
        if (contextSnippet == null) {
            return "";
        }
        return secretValue.isEmpty() ? contextSnippet : contextSnippet.replace(secretValue, getMaskedSecret());
    }
}
