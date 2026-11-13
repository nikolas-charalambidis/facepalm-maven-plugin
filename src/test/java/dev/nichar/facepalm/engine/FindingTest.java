/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.nichar.facepalm.config.ScoringConfig;
import org.junit.jupiter.api.Test;

class FindingTest {

    @Test
    void masked_secret_hides_short_secrets_completely() {
        assertEquals("****", finding("p4ssw0r", "").getMaskedSecret());
    }

    @Test
    void masked_secret_reveals_at_most_a_quarter() {
        // 9 characters: 1 at each end (previously 8 of 9 characters were revealed).
        assertEquals("p...9", finding("p4ssw0rd9", "").getMaskedSecret());
        assertEquals("ab...op", finding("abcdefghijklmnop", "").getMaskedSecret());
    }

    @Test
    void masked_secret_reveals_at_most_four_characters_per_end() {
        assertEquals("AKIA...MPLE", finding("AKIAIOSFODNN7EXAMPLEAKIAIOSFODNN7EXAMPLE", "").getMaskedSecret());
    }

    @Test
    void masked_snippet_replaces_every_occurrence_of_the_secret() {
        final var secret = "s3cr3t-value-123";
        final var snippet = "password=" + secret + " # copy of " + secret;

        final var masked = finding(secret, snippet).getMaskedSnippet();

        assertFalse(masked.contains(secret), masked);
        assertEquals("password=s3...23 # copy of s3...23", masked);
    }

    @Test
    void masked_snippet_handles_missing_snippet() {
        assertEquals("", finding("s3cr3t-value-123", null).getMaskedSnippet());
    }

    @Test
    void log_adjusts_scores_within_bounds_and_records_history() {
        final var finding = Finding.builder()
            .patternName("Test")
            .deduplicationHash("hash")
            .secretValue("s3cr3t-value-123")
            .riskScore(90)
            .confidenceScore(10)
            .build();

        finding.log("Raise", 20, -30);
        finding.log("Lower", -5, 5);

        assertThat(finding.getRiskScore()).isEqualTo(95);
        assertThat(finding.getConfidenceScore()).isEqualTo(5);
        assertThat(finding.getScoreHistory()).containsExactly("Raise (+20/-30)", "Lower (-5/+5)");
    }

    @Test
    void severity_follows_the_configured_thresholds() {
        // Default thresholds: warning 40, error 80; AVERAGE makes the score the mean of risk and confidence.
        final var config = new ScoringConfig();
        config.setStrategy(ScoringStrategy.AVERAGE);

        assertThat(scored(80, 80).getSeverity(config)).isEqualTo(Severity.ERROR);
        assertThat(scored(80, 79).getSeverity(config)).isEqualTo(Severity.WARNING);
        assertThat(scored(40, 40).getSeverity(config)).isEqualTo(Severity.WARNING);
        assertThat(scored(40, 39).getSeverity(config)).isEqualTo(Severity.INFO);
        assertThat(scored(80, 79).getNumericScore(config)).isEqualTo(79.5);
    }

    private static Finding scored(final int risk, final int confidence) {
        return Finding.builder()
            .patternName("Test")
            .deduplicationHash("hash")
            .secretValue("s3cr3t-value-123")
            .riskScore(risk)
            .confidenceScore(confidence)
            .build();
    }

    private static Finding finding(final String secret, final String snippet) {
        return Finding.builder()
            .patternName("Test")
            .deduplicationHash("hash")
            .secretValue(secret)
            .contextSnippet(snippet)
            .build();
    }
}
