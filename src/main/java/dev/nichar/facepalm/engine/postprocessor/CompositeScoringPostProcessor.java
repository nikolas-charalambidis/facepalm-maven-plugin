/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.postprocessor;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.Severity;
import dev.nichar.facepalm.pattern.SecretPattern;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Refines discovery findings based on the collective context of a file.
 * Elevates risk for files with multiple distinct secret types and discounts high-volume noise.
 */
@Named
@Singleton
class CompositeScoringPostProcessor implements FileFindingsPostProcessor {

    /**
     * Base confidence from which a pattern counts as format-specific: a fixed prefix or structure (AWS key IDs, GitHub,
     * Stripe, private keys, connection strings) that identifies a real credential, not noise.
     */
    private static final int FORMAT_SPECIFIC_CONFIDENCE = 90;

    @Inject
    private FacepalmConfig config;

    @Override
    public void process(@Nonnull final List<Finding> fileFindings, @Nonnull final FileContext context) {
        if (fileFindings.isEmpty()) {
            return;
        }

        final var uniquePatterns = fileFindings.stream()
            .map(Finding::getPatternName)
            .distinct()
            .count();
        // Only findings that would be reported count toward the volume: INFO findings, such as references to
        // environment variables, must not push a real secret over the threshold (#275).
        final var scoring = config.getScoring();
        final var reportedInFile = fileFindings.stream()
            .filter(finding -> finding.getSeverity(scoring) != Severity.INFO)
            .count();
        final Set<String> formatSpecific = config.getPatterns().getOverrides().stream()
            .filter(pattern -> pattern.getBaseConfidence() >= FORMAT_SPECIFIC_CONFIDENCE)
            .map(SecretPattern::getName)
            .collect(Collectors.toSet());

        final var conf = config.getPostProcessing();
        for (final var finding : fileFindings) {
            // Categorize file as "noisy" if detections exceed the high-volume threshold. High volume often indicates
            // log dumps, public datasets, or false positives. Format-specific secrets are real credentials however
            // many there are, so more leaked keys never make a file pass (#275).
            if (reportedInFile > conf.getHighVolumeThreshold() && !formatSpecific.contains(finding.getPatternName())) {
                finding.log("High Volume File (Threshold: " + conf.getHighVolumeThreshold() + ")", -25, -30);
            }
            // Elevate risk if a single file contains multiple distinct secret types.
            if (uniquePatterns > 1) {
                // Increases risk because a file containing multiple secret types is statistically more likely to be a
                // real credential leak.
                finding.log("Composite Risk: Multiple distinct secrets in one file", 15, 10);
            }
        }
    }
}
