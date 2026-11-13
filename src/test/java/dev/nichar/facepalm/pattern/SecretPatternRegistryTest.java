/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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

    /**
     * Long lines (e.g. minified JavaScript) must be matched in linear time. Before issue #63, unbounded gaps made a
     * 100 KB line take seconds per pattern and the database URI patterns about 25 seconds.
     */
    @Test
    void default_patterns_match_long_lines_in_linear_time() {
        final int length = 500_000;
        final List<String> lines = List.of(
            "openai.apiKey(x);var y=1;".repeat(length / 25),
            "heroku=x;".repeat(length / 9),
            "mssql password ".repeat(length / 15),
            "mongodb://" + "a:".repeat(length / 2),
            "postgres://" + "a:".repeat(length / 2),
            "mysql://" + "a:".repeat(length / 2),
            "redis://" + "a:".repeat(length / 2),
            "eyJ" + "a.".repeat(length / 2),
            "token=".repeat(length / 6),
            "a.b(c,d);".repeat(length / 9));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (final var line : lines) {
                for (final var pattern : SecretPatternRegistry.DEFAULT_PATTERNS) {
                    final var matcher = pattern.getPattern().matcher(line);
                    while (matcher.find()) {
                        // Exhaust all matches, as the extractor does.
                    }
                }
            }
        });
    }

    @Test
    void bounded_patterns_still_detect_secrets() {
        assertEquals("admin:p%40ss%21w0rd",
            group("MongoDB Connection", "uri=mongodb+srv://admin:p%40ss%21w0rd@cluster0.example.net/db"));
        assertEquals("app:s3cr3t:with:colons",
            group("PostgreSQL Connection", "jdbc:postgresql://app:s3cr3t:with:colons@db.example.com:5432/app"));
        assertEquals("root:hunter2hunter2", group("MySQL Connection", "mysql://root:hunter2hunter2@localhost/app"));
        assertEquals("r3d1sP4ss", group("Redis Password", "REDIS_URL=redis://:r3d1sP4ss@cache:6379"));
        assertEquals("01234567-89ab-cdef-0123-456789abcdef",
            group("Heroku API Key", "HEROKU_API_KEY=01234567-89ab-cdef-0123-456789abcdef"));
        assertEquals("0123456789abcdef0123456789abcdef",
            group("Azure OpenAI Key", "azure.openai.api-key: 0123456789abcdef0123456789abcdef"));
        assertEquals("Sup3rS3cret!",
            group("SQL Server Password", "mssql.datasource.password=Sup3rS3cret!"));
    }

    /**
     * Issue #204: code that reads a secret is not a secret. The extractor strips quotes before matching.
     */
    @Test
    void generic_patterns_ignore_method_calls() {
        for (final var line : List.of(
            "String secret = env.getProperty(STRIPE_WEBHOOK_SECRET);",
            "String token = Objects.requireNonNull(System.getenv(GITHUB_TOKEN));",
            "String accessToken = jwtService.generateToken(userDetails);",
            "String apiKey = System.getenv(OPENAI_API_KEY);",
            "String clientSecret = properties.getClientSecret();",
            "String token = tokens.issue(userDetails, ACCESS_TOKEN_TTL);",
            "val token = $tokenProvider.issueToken(user)")) {
            assertFalse(pattern("Generic API Key Assignment").matcher(line).find(), () -> "Falsely detected: " + line);
        }
        for (final var line : List.of(
            "String password = env.getProperty(spring.datasource.password);",
            "char[] password = console.readPassword();")) {
            assertFalse(pattern("Generic Password Assignment").matcher(line).find(), () -> "Falsely detected: " + line);
        }
    }

    @Test
    void generic_patterns_still_detect_values_that_are_not_calls() {
        assertEquals("Zq8vLm3NpR7tXw2YkB9cHd4F", group("Generic API Key Assignment",
            "api_key=Zq8vLm3NpR7tXw2YkB9cHd4F"));
        assertEquals("s3cr3t.value.with.dots1", group("Generic API Key Assignment", "secret: s3cr3t.value.with.dots1"));
        assertEquals("aGVsbG8+d29ybGQ/Zm9vYmFyMTI=", group("Generic API Key Assignment",
            "token=aGVsbG8+d29ybGQ/Zm9vYmFyMTI="));
        assertEquals("P@ss(word)1", group("Generic Password Assignment", "password=P@ss(word)1"));
        assertEquals("hunter2hunter2", group("Generic Password Assignment", "db.password=hunter2hunter2"));
    }

    /**
     * A call with a literal secret argument still contains the secret, unlike a call that only names it (#204).
     */
    @Test
    void generic_patterns_detect_calls_with_literal_secret_arguments() {
        for (final var line : List.of(
            "String token = env.setVariable(Zq8vLm3NpR7tXw2YkB9cHd4F);",
            "String token = wrap(Zq8vLm3NpR7tXw2YkB9cHd4F);",
            "String apiKey = Objects.requireNonNull(decode(Zq8vLm3NpR7tXw2YkB9cHd4F));",
            "String secret = totp(JBSWY3DPEHPK3PXP);")) {
            assertTrue(pattern("Generic API Key Assignment").matcher(line).find(), () -> "Not detected: " + line);
        }
        assertTrue(pattern("Generic Password Assignment").matcher("password = encrypt(Xk9mQ2vLp7zR4wq)").find());
    }

    /**
     * Issue #205: keywords followed by a suffix, as in the most common environment variable names.
     */
    @Test
    void keyword_with_suffix_is_detected() {
        final var key = "Zq8vLm3NpR7tXw2YkB9cHd4FAb1/Cd2+Ef3=Gh4Ij5Kl";
        assertEquals(key.substring(0, 40), group("AWS Secret Key", "AWS_SECRET_ACCESS_KEY=" + key));
        assertEquals(key.substring(0, 40), group("AWS Secret Key", "aws_secret_access_key = " + key));
        assertEquals("rk_live_Zq8vLm3NpR7tXw2YkB9c",
            group("Generic API Key Assignment", "STRIPE_SECRET_KEY=rk_live_Zq8vLm3NpR7tXw2YkB9c"));
        assertEquals("q8Zr4vLm3NpR7tXw2YkB9cHd",
            group("Generic API Key Assignment", "JWT_SECRET_KEY=q8Zr4vLm3NpR7tXw2YkB9cHd"));
        assertEquals("SG.Zq8vLm3NpR7tXw2YkB9c",
            group("Generic API Key Assignment", "SENDGRID_API_KEY_ID=SG.Zq8vLm3NpR7tXw2YkB9c"));
        assertEquals("django-insecure-q8Zr4vLm3NpR7tXw",
            group("Generic API Key Assignment", "SECRET_KEY = django-insecure-q8Zr4vLm3NpR7tXw"));
        assertEquals("Zq8vLm3NpR7tXw2YkB9cHd4F",
            group("Generic API Key Assignment", "github.token.value=Zq8vLm3NpR7tXw2YkB9cHd4F"));
        assertEquals("Xk9mQ2vLp7zR4wq", group("Generic Password Assignment", "DB_PASS=Xk9mQ2vLp7zR4wq"));
        assertEquals("correct-horse-battery", group("Generic Password Assignment",
            "gpg.passphrase=correct-horse-battery"));
    }

    @Test
    void keyword_suffix_describing_metadata_or_other_words_is_not_detected() {
        for (final var line : List.of(
            "SECRET_NAME=my-kubernetes-secret-prod",
            "secret.name.prefix=my-kubernetes-secret",
            "token.ttl=86400000000000000",
            "JWT_TOKEN_HEADER=Authorization-Bearer-Token",
            "api_key_url=https://api.example.com/v1/keys",
            "secretary=Zq8vLm3NpR7tXw2YkB9cHd4F",
            "tokenizer=Zq8vLm3NpR7tXw2YkB9cHd4F")) {
            assertFalse(pattern("Generic API Key Assignment").matcher(line).find(), () -> "Falsely detected: " + line);
        }
        for (final var line : List.of("bypass=Xk9mQ2vLp7zR4wq", "compass: Xk9mQ2vLp7zR4wq")) {
            assertFalse(pattern("Generic Password Assignment").matcher(line).find(), () -> "Falsely detected: " + line);
        }
    }

    private static String group(final String patternName, final String line) {
        final var matcher = pattern(patternName).matcher(line);
        assertTrue(matcher.find(), () -> patternName + " not detected in: " + line);
        return matcher.group(1);
    }

    private static Pattern privateKeyPattern() {
        return pattern("Private Key Block");
    }

    private static Pattern pattern(final String name) {
        return SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .filter(p -> name.equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getPattern();
    }

    private static String pem(final String beginLabel, final String endLabel) {
        return DASHES + "BEGIN " + beginLabel + DASHES + KEY_BODY + DASHES + "END " + endLabel + DASHES;
    }
}
