/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private static Finding finding(final String secret, final String snippet) {
        return Finding.builder()
            .patternName("Test")
            .deduplicationHash("hash")
            .secretValue(secret)
            .contextSnippet(snippet)
            .build();
    }
}
