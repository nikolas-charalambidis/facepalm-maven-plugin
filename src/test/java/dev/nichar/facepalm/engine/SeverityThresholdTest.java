/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nichar.facepalm.config.ScoringConfig;
import org.junit.jupiter.api.Test;

/**
 * Severity boundaries: a score equal to a threshold reaches it.
 */
class SeverityThresholdTest {

    @Test
    void severity_boundaries_with_documented_thresholds() {
        final var config = config(ScoringStrategy.AVERAGE, 80, 40);

        assertEquals(Severity.ERROR, severity(config, 80, 80)); // 80.0
        assertEquals(Severity.WARNING, severity(config, 80, 79)); // 79.5
        assertEquals(Severity.WARNING, severity(config, 40, 40)); // 40.0
        assertEquals(Severity.INFO, severity(config, 40, 39)); // 39.5
    }

    @Test
    void strategies_compute_documented_formulas() {
        assertEquals(60.0, ScoringStrategy.AVERAGE.calculate(80, 40));
        assertEquals(Math.sqrt(80 * 40.0), ScoringStrategy.GEOMETRIC.calculate(80, 40));
        assertEquals(Math.sqrt((80 * 80 + 40 * 40) / 2.0), ScoringStrategy.ROOT_MEAN_SQUARE.calculate(80, 40));
        assertEquals(64 * 0.8 + 40 * 0.2, ScoringStrategy.WEIGHTED_QUADRATIC.calculate(80, 40), 1e-9);
        assertEquals(95.0, ScoringStrategy.GATEKEEPER.calculate(90, 95)); // risk >= 90: maximum
        assertEquals(60.0, ScoringStrategy.GATEKEEPER.calculate(89, 31)); // otherwise: average
    }

    @Test
    void every_strategy_maps_full_scores_to_100_and_zero_to_0() {
        for (final var strategy : ScoringStrategy.values()) {
            assertEquals(100.0, strategy.calculate(100, 100), 1e-9, strategy.name());
            assertEquals(0.0, strategy.calculate(0, 0), 1e-9, strategy.name());
        }
    }

    private static Severity severity(final ScoringConfig config, final int risk, final int confidence) {
        return Finding.builder().patternName("Test").deduplicationHash("h").secretValue("v")
            .riskScore(risk).confidenceScore(confidence).build().getSeverity(config);
    }

    private static ScoringConfig config(final ScoringStrategy strategy, final int error, final int warning) {
        final var config = new ScoringConfig();
        config.setStrategy(strategy);
        config.setErrorThreshold(error);
        config.setWarningThreshold(warning);
        return config;
    }
}
