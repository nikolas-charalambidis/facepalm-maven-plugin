/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests for the default secret detection pattern registry.
 */
class SecretPatternRegistryTest {

    /**
     * PEM boundary dashes, kept out of literal key headers so this test file is not itself flagged by secret scanners.
     */
    private static final String DASHES = "-----";

    private static final String KEY_BODY = "\nMIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEAFAKEKEYFORTESTS\nONLYnotARealKey0123456789==\n";

    @Test
    void default_patterns_do_not_register_duplicate_regexes() {
        final var duplicatePatterns = SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .collect(Collectors.groupingBy(pattern -> pattern.getPattern().pattern(), Collectors.counting()))
            .entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .toList();

        assertTrue(duplicatePatterns.isEmpty(), () -> "Duplicate default secret patterns: " + duplicatePatterns);
    }

    /**
     * Every common private key encoding must be detected, including PKCS#8 without a key type (issue #45).
     */
    @Test
    void private_key_block_matches_all_private_key_labels() {
        final Pattern pattern = privateKeyPattern();
        for (final String label : List.of(
            "PRIVATE KEY",
            "ENCRYPTED PRIVATE KEY",
            "RSA PRIVATE KEY",
            "EC PRIVATE KEY",
            "DSA PRIVATE KEY",
            "OPENSSH PRIVATE KEY",
            "PGP PRIVATE KEY BLOCK")) {
            assertTrue(pattern.matcher(pem(label, label)).find(), () -> "Not detected: " + label);
        }
    }

    @Test
    void private_key_block_ignores_public_keys_and_certificates() {
        final Pattern pattern = privateKeyPattern();
        for (final String label : List.of("PUBLIC KEY", "RSA PUBLIC KEY", "CERTIFICATE", "PGP PUBLIC KEY BLOCK")) {
            assertFalse(pattern.matcher(pem(label, label)).find(), () -> "Falsely detected: " + label);
        }
    }

    @Test
    void private_key_block_requires_matching_end_label() {
        assertFalse(privateKeyPattern().matcher(pem("RSA PRIVATE KEY", "EC PRIVATE KEY")).find());
    }

    private static Pattern privateKeyPattern() {
        return SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .filter(p -> "Private Key Block".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getPattern();
    }

    private static String pem(final String beginLabel, final String endLabel) {
        return DASHES + "BEGIN " + beginLabel + DASHES + KEY_BODY + DASHES + "END " + endLabel + DASHES;
    }
}
