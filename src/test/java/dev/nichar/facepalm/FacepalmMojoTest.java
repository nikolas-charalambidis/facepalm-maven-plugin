/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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

    @Test
    void execution_failure_falls_back_to_the_exception_type() {
        assertEquals("Facepalm scan failed: java.lang.NullPointerException",
            FacepalmMojo.executionFailure(new NullPointerException()).getMessage());
    }
}
