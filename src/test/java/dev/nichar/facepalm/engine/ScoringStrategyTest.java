/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Composite scores of every {@link ScoringStrategy}.
 */
class ScoringStrategyTest {

    @ParameterizedTest(name = "{0}({1}, {2}) = {3}")
    @CsvSource({
        "AVERAGE,            80,  60,  70.0",
        "GEOMETRIC,          80,  20,  40.0",
        "ROOT_MEAN_SQUARE,   60,  80,  70.7107",
        "WEIGHTED_QUADRATIC, 50, 100,  40.0",
        "WEIGHTED_QUADRATIC, 80,  70,  65.2",
        "GATEKEEPER,         89,  40,  64.5",
        "GATEKEEPER,         90,  40,  90.0",
        "GATEKEEPER,         95, 100, 100.0"
    })
    void calculates_composite_score(final ScoringStrategy strategy,
                                    final int risk,
                                    final int confidence,
                                    final double expected) {
        assertThat(strategy.calculate(risk, confidence)).isCloseTo(expected, within(0.0001));
    }

    @ParameterizedTest
    @EnumSource(ScoringStrategy.class)
    void maps_the_score_range_onto_itself(final ScoringStrategy strategy) {
        assertThat(strategy.calculate(0, 0)).isZero();
        assertThat(strategy.calculate(100, 100)).isEqualTo(100.0);
    }

    @ParameterizedTest
    @EnumSource(ScoringStrategy.class)
    void never_decreases_when_risk_or_confidence_grows(final ScoringStrategy strategy) {
        for (int value = 0; value < 100; value += 10) {
            assertThat(strategy.calculate(value + 10, 50)).isGreaterThanOrEqualTo(strategy.calculate(value, 50));
            assertThat(strategy.calculate(50, value + 10)).isGreaterThanOrEqualTo(strategy.calculate(50, value));
        }
    }
}
