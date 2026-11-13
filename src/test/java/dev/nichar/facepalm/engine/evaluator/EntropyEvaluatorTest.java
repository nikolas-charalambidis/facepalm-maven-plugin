/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shannon entropy raises random-looking secrets and discounts repetitive ones.
 */
class EntropyEvaluatorTest {

    private final EntropyEvaluator evaluator = new EntropyEvaluator();

    @Test
    void high_entropy_raises_risk_and_confidence() {
        // 36 distinct characters: about 5.17 bits per character.
        final var finding = evaluate("Generic API Key Assignment", "abcdefghijklmnopqrstuvwxyz0123456789");

        assertThat(finding.getRiskScore()).isEqualTo(60);
        assertThat(finding.getConfidenceScore()).isEqualTo(70);
        assertThat(finding.getScoreHistory()).singleElement().asString().startsWith("High Entropy (5.17)");
    }

    @Test
    void low_entropy_lowers_confidence_only() {
        final var finding = evaluate("Generic Password Assignment", "aaaaaaaaaaaaaaaa");

        assertThat(finding.getRiskScore()).isEqualTo(50);
        assertThat(finding.getConfidenceScore()).isEqualTo(10);
        assertThat(finding.getScoreHistory()).singleElement().asString().startsWith("Low Entropy (0.00)");
    }

    @Test
    void medium_entropy_leaves_scores_unchanged() {
        // 8 distinct characters: exactly 3.0 bits, neither low (< 3.0) nor high (> 4.5).
        final var finding = evaluate("Generic Password Assignment", "abcdefgh");

        assertThat(finding.getRiskScore()).isEqualTo(50);
        assertThat(finding.getConfidenceScore()).isEqualTo(50);
        assertThat(finding.getScoreHistory()).isEmpty();
    }

    @Test
    void private_keys_are_skipped() {
        final var finding = evaluate("Private Key Block", "aaaaaaaaaaaaaaaa");

        assertThat(finding.getConfidenceScore()).isEqualTo(50);
        assertThat(finding.getScoreHistory()).isEmpty();
    }

    /**
     * Issue #206: a 20-character value can never exceed log2(20) = 4.32 bits, so a fixed 4.5 threshold never
     * rewarded short random keys such as AWS access key IDs.
     */
    @Test
    void short_random_value_gets_the_high_entropy_bonus() {
        final var finding = evaluate("AWS Access Key", "AKIAQ7XKZ3M4PL2NWR8T");

        assertThat(finding.getRiskScore()).isEqualTo(60);
        assertThat(finding.getConfidenceScore()).isEqualTo(70);
        assertThat(finding.getScoreHistory()).singleElement().asString().startsWith("High Entropy");
    }

    @Test
    void values_shorter_than_twelve_characters_get_no_high_entropy_bonus() {
        final var finding = evaluate("Generic Password Assignment", "Xk9#mQ2vLp7");

        assertThat(finding.getScoreHistory()).isEmpty();
    }

    @Test
    void high_entropy_threshold_is_relative_to_length_and_capped() {
        assertThat(EntropyEvaluator.highEntropyThreshold(20)).isCloseTo(3.89, within(0.01));
        assertThat(EntropyEvaluator.highEntropyThreshold(32)).isEqualTo(4.5);
        assertThat(EntropyEvaluator.highEntropyThreshold(1024)).isEqualTo(4.5);
    }

    private Finding evaluate(final String patternName, final String secret) {
        final var finding = Finding.builder()
            .patternName(patternName)
            .deduplicationHash("hash")
            .secretValue(secret)
            .riskScore(50)
            .confidenceScore(50)
            .build();
        evaluator.evaluate(finding, new FileContext(Path.of("application.properties"), "", List.of()));
        return finding;
    }
}
