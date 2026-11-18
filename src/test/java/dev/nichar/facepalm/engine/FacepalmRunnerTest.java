/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.config.ScoringConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * Issue #228: a scan that aborts must not leave the results of an earlier run, which the report would render as
     * current.
     */
    @Test
    void aborted_scan_leaves_no_results_of_an_earlier_run(@TempDir final Path tempDir) throws Exception {
        final var root = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(root.resolve("app.properties"), "name=value\n");
        final var output = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(output.resolve("facepalm-findings.json"), "[]");
        Files.writeString(output.resolve("facepalm-statistics.json"), "{}");
        final var config = new FacepalmConfig();
        // An invalid regex aborts the scan before any result is written.
        config.getEngine().setSkipBinaryRegex("(");

        assertThrows(PatternSyntaxException.class,
            () -> TestContainer.create(config).getInstance(FacepalmRunner.class).run(root, output, "test"));

        assertFalse(Files.exists(output.resolve("facepalm-findings.json")));
        assertFalse(Files.exists(output.resolve("facepalm-statistics.json")));
    }

    private static ScoringConfig scoring(final boolean failOnError, final boolean failOnWarnings) {
        final var scoring = new ScoringConfig();
        scoring.setFailOnError(failOnError);
        scoring.setFailOnWarnings(failOnWarnings);
        return scoring;
    }
}
