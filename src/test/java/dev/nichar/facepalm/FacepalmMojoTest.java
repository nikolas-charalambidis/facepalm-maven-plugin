/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.config.PatternConfig;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

class FacepalmMojoTest {

    /**
     * Issue #72: the build error said "Unknown exception" and hid the cause.
     */
    @Test
    void execution_failure_states_the_cause() {
        final var cause = new IOException("Permission denied: target/facepalm-findings.json");

        final var failure = FacepalmMojo.executionFailure(cause);

        assertEquals("Facepalm scan failed: Permission denied: target/facepalm-findings.json", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void execution_failure_states_an_invalid_regex() {
        PatternSyntaxException cause = null;
        try {
            Pattern.compile("[");
        } catch (PatternSyntaxException e) {
            cause = e;
        }

        assertEquals("Facepalm scan failed: " + cause.getMessage(),
            FacepalmMojo.executionFailure(cause).getMessage());
    }

    /**
     * Issue #277: an empty {@code <patterns><overrides/>} scanned with zero patterns and passed every build.
     */
    @Test
    void configured_pattern_overrides_fail_the_build() {
        final var failure = assertThrows(MojoExecutionException.class,
            () -> FacepalmMojo.requireNoCustomPatterns(new PatternConfig(List.of())));

        assertTrue(failure.getMessage().contains("not supported yet"), failure.getMessage());
        assertTrue(failure.getMessage().contains("/issues/79"), failure.getMessage());
    }

    @Test
    void absent_pattern_overrides_pass() {
        assertDoesNotThrow(() -> FacepalmMojo.requireNoCustomPatterns(new PatternConfig()));
        assertDoesNotThrow(() -> FacepalmMojo.requireNoCustomPatterns(null));
    }

    /**
     * Issue #277: outside the Mojo (CLI, tests), an empty list still scans with the built-in patterns.
     */
    @Test
    void empty_pattern_overrides_fall_back_to_the_defaults() {
        assertEquals(SecretPatternRegistry.DEFAULT_PATTERNS, new PatternConfig(List.of()).getOverrides());
        assertEquals(SecretPatternRegistry.DEFAULT_PATTERNS, new PatternConfig().getOverrides());
    }

    @Test
    void execution_failure_falls_back_to_the_exception_type() {
        assertEquals("Facepalm scan failed: java.lang.NullPointerException",
            FacepalmMojo.executionFailure(new NullPointerException()).getMessage());
    }
}
