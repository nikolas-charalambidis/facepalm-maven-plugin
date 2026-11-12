/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvaluatorConfigTest {

    private final EvaluatorConfig config = new EvaluatorConfig();

    @Test
    void interpolation_pattern_matches_placeholders() {
        assertTrue(isPlaceholder("${DB_PASSWORD}"));
        assertTrue(isPlaceholder("jdbc:postgresql://${DB_HOST}/app"));
        assertTrue(isPlaceholder("{{ secrets.API_KEY }}"));
        assertTrue(isPlaceholder("%DB_PASSWORD%"));
        assertTrue(isPlaceholder("<your-api-key>"));
        assertTrue(isPlaceholder("[YOUR TOKEN]"));
    }

    @Test
    void interpolation_pattern_does_not_match_real_secrets() {
        // URL-encoding is mandatory for special characters in database URIs.
        assertFalse(isPlaceholder("mongodb://admin:p%40ss%21w0rd@host"));
        assertFalse(isPlaceholder("p%C3%A9ss%21word"));
        assertFalse(isPlaceholder("s3cr3t<x>tail"));
        assertFalse(isPlaceholder("abc[0]def"));
        assertFalse(isPlaceholder("ghp_s3cr3tV4lu3Th4tMustN3v3rL34k0000"));
    }

    private boolean isPlaceholder(final String value) {
        return config.getInterpolationPattern().matcher(value).matches();
    }
}
