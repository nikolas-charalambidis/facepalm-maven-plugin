/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.nichar.facepalm.config.ScoringConfig;
import org.junit.jupiter.api.Test;

/**
 * Build failure semantics of {@code failOnError} and {@code failOnWarnings} for every flag combination.
 */
class FacepalmRunnerTest {

    @Test
    void no_flags_never_fail() {
        assertNull(FacepalmRunner.failureMessage(scoring(false, false), 3, 2));
    }

    @Test
    void fail_on_error_fails_only_on_errors() {
        assertEquals("Facepalm scan failed: 1 critical findings detected.",
            FacepalmRunner.failureMessage(scoring(true, false), 1, 0));
        assertNull(FacepalmRunner.failureMessage(scoring(true, false), 0, 5));
    }

    @Test
    void fail_on_warnings_counts_errors_too() {
        // Issue #66: with failOnError=false, critical findings passed while mild ones failed.
        assertEquals("Facepalm scan failed: 2 findings at or above the warning threshold "
            + "(2 critical, 0 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(false, true), 2, 0));
        assertEquals("Facepalm scan failed: 1 findings at or above the warning threshold "
            + "(0 critical, 1 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(false, true), 0, 1));
    }

    @Test
    void both_flags_report_errors_first() {
        assertEquals("Facepalm scan failed: 1 critical findings detected.",
            FacepalmRunner.failureMessage(scoring(true, true), 1, 1));
        assertEquals("Facepalm scan failed: 1 findings at or above the warning threshold "
            + "(0 critical, 1 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(true, true), 0, 1));
    }

    @Test
    void no_findings_never_fail() {
        assertNull(FacepalmRunner.failureMessage(scoring(true, true), 0, 0));
    }

    private static ScoringConfig scoring(final boolean failOnError, final boolean failOnWarnings) {
        final var scoring = new ScoringConfig();
        scoring.setFailOnError(failOnError);
        scoring.setFailOnWarnings(failOnWarnings);
        return scoring;
    }
}
