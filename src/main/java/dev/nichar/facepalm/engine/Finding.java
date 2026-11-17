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

    /**
     * Source line of the finding, with every secret on it already masked by the extractor.
     */
    private final String contextSnippet;

    private int riskScore;

    private int confidenceScore;

    /**
     * Historical log of scoring adjustments.
     */
    @Builder.Default
    private final List<String> scoreHistory = new ArrayList<>();

    /**
     * Severity that does not depend on the score, for a finding that is not a secret, such as an unused suppression
     * marker (#12). {@code null} for secrets: their severity follows the score.
     */
    private final Severity fixedSeverity;

    /**
     * Whether an inline {@code facepalm:ignore} marker suppresses the finding (#12).
     */
    private boolean suppressed;

    /**
     * Reason written after the marker, or {@code null} if the marker has none.
     */
    private String suppressionReason;

    /**
     * Marks the finding as suppressed by an inline marker, with an optional reason, and records it in the history.
     */
    public void suppress(final String reason) {
        suppressed = true;
        suppressionReason = reason;
        scoreHistory.add("Suppressed by facepalm:ignore" + (reason == null ? "" : ": " + reason));
    }

    /**
     * Updates threat scores and records the rule responsible for the adjustment.
     */
    public void log(@Nonnull final String rule, final int rDelta, final int cDelta) {
        riskScore = Math.max(0, Math.min(100, riskScore + rDelta));
        confidenceScore = Math.max(0, Math.min(100, confidenceScore + cDelta));
        scoreHistory.add(String.format(Locale.ROOT, "%s (%+d/%+d)", rule, rDelta, cDelta));
    }

    /**
     * Resolves the finding's severity based on configured threat thresholds. A suppressed finding is
     * {@link Severity#SUPPRESSED}, and a finding with a {@link #fixedSeverity} keeps it, whatever the score.
     */
    @Nonnull
    public Severity getSeverity(@Nonnull final ScoringConfig config) {
        if (suppressed) {
            return Severity.SUPPRESSED;
        }
        if (fixedSeverity != null) {
            return fixedSeverity;
        }
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
        // A finding with a fixed severity is not a secret: its value is text the scanner wrote, such as a marker.
        return fixedSeverity != null ? secretValue : mask(secretValue);
    }

    /**
     * Masks a value as {@link #getMaskedSecret()} does: at most a quarter of it is revealed (up to 4 characters at
     * each end); values shorter than 8 characters are fully masked.
     */
    @Nonnull
    public static String mask(@Nonnull final String value) {
        final int reveal = Math.min(4, value.length() / 8);
        return reveal == 0 ? "****" : value.substring(0, reveal) + "..." + value.substring(value.length() - reveal);
    }

    /**
     * Returns the context snippet with every occurrence of the secret replaced by its masked form.
     * The extractor already masks every secret on the line by position (#218); this replacement is a second line of
     * defense for snippets built elsewhere. The raw snippet must never leave the process (logs, JSON, HTML, SARIF).
     */
    @Nonnull
    public String getMaskedSnippet() {
        if (contextSnippet == null) {
            return "";
        }
        return secretValue.isEmpty() ? contextSnippet : contextSnippet.replace(secretValue, getMaskedSecret());
    }
}
