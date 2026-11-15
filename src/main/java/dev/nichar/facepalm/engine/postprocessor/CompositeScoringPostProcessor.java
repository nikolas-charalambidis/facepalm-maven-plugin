/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.postprocessor;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.Severity;
import dev.nichar.facepalm.pattern.SecretPattern;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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

    /**
     * Maven-encrypted value as the whole secret: Maven 3 {@code {base64}} or Maven 4
     * {@code {[name=master,cipher=...,version=4.0]base64}}, as written by {@code mvn --encrypt-password} or
     * {@code mvnenc}. Possessive and bounded, so it runs in linear time.
     */
    private static final Pattern MAVEN_ENCRYPTED_VALUE = Pattern.compile(
        "\\{(?:\\[[^\\]\\s{}]{1,200}+\\])?+[A-Za-z0-9+/]{8,}+=*+\\}");

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
            // Last, after every other adjustment, so no later bonus lifts it back to ERROR.
            capMavenEncryptedValue(finding, scoring);
        }
    }

    /**
     * A Maven-encrypted value follows Maven's recommended practice, but anyone with the master password in
     * {@code settings-security.xml} can decrypt it (#296). It keeps the score of a plain value, capped just below the
     * error threshold: reported as a WARNING (or lower) with any scoring strategy and thresholds, so it fails the build
     * only with {@code failOnWarnings}. Risk is lowered first, then confidence.
     */
    private static void capMavenEncryptedValue(@Nonnull final Finding finding, @Nonnull final ScoringConfig scoring) {
        if (finding.getSeverity(scoring) != Severity.ERROR
            || !MAVEN_ENCRYPTED_VALUE.matcher(finding.getSecretValue().trim()).matches()) {
            return;
        }
        final var strategy = scoring.getStrategy();
        final double threshold = scoring.getErrorThreshold();
        var risk = finding.getRiskScore();
        var confidence = finding.getConfidenceScore();
        while (risk > 0 && strategy.calculate(risk, confidence) >= threshold) {
            risk--;
        }
        while (confidence > 0 && strategy.calculate(risk, confidence) >= threshold) {
            confidence--;
        }
        finding.log("Maven Encrypted Value (capped below error threshold)", risk - finding.getRiskScore(),
            confidence - finding.getConfidenceScore());
    }
}
