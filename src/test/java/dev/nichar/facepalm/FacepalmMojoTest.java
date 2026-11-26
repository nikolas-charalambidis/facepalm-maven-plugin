/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.nichar.facepalm.config.PatternConfig;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
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
    void empty_and_absent_pattern_overrides_pass() {
        assertDoesNotThrow(() -> FacepalmMojo.validatePatterns(new PatternConfig(List.of())));
        assertDoesNotThrow(() -> FacepalmMojo.validatePatterns(new PatternConfig()));
        assertDoesNotThrow(() -> FacepalmMojo.validatePatterns(null));
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

    /**
     * Issue #38: {@code facepalm.skip} skips the scan before any configuration is checked or the engine starts.
     */
    @Test
    void skip_returns_before_scanning() throws Exception {
        final var messages = new ArrayList<String>();
        final var mojo = new FacepalmMojo();
        mojo.setLog(new SystemStreamLog() {

            @Override
            public void info(final CharSequence content) {
                messages.add(content.toString());
            }
        });
        set(mojo, "skip", true);
        // The plugin descriptor is not set, so execution would fail if the scan ran.
        set(mojo, "patterns", new PatternConfig(List.of()));

        assertDoesNotThrow(mojo::execute);
        assertEquals(List.of("Skipping Facepalm scan (facepalm.skip=true)."), messages);
    }

    @Test
    void invalid_patterns_are_wrapped_as_configuration_failures() {
        final var patterns = new PatternConfig();
        patterns.setDisabledPatterns(java.util.Set.of("Unknown"));
        final var failure = org.junit.jupiter.api.Assertions.assertThrows(MojoExecutionException.class,
            () -> FacepalmMojo.validatePatterns(patterns));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Unknown disabled pattern"));
    }

    private static void set(final Object target, final String name, final Object value)
            throws ReflectiveOperationException {
        final Field field = FacepalmMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
