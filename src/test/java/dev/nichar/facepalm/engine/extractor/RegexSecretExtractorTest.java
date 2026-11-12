/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexSecretExtractorTest {

    /**
     * Issue #69: an optional group that did not participate in the match returned null, and the entropy evaluator
     * then threw a NullPointerException that dropped all findings of the file.
     */
    @Test
    void optional_group_that_did_not_match_falls_back_to_full_match() {
        assertEquals("bar", secret("(foo)?bar", "bar"));
    }

    @Test
    void group_one_is_preferred_when_it_matched() {
        assertEquals("foo", secret("(foo)?bar", "foobar"));
    }

    @Test
    void pattern_without_groups_returns_full_match() {
        assertEquals("ghp_abc", secret("ghp_[a-z]+", "token ghp_abc"));
    }

    private static String secret(final String regex, final String input) {
        final var matcher = Pattern.compile(regex).matcher(input);
        assertTrue(matcher.find());
        return RegexSecretExtractor.secretValue(matcher);
    }
}
