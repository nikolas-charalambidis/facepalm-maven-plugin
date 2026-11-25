/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #386: values that cannot be secrets. Random values are assembled at runtime, so this file contains no literal
 * token that other secret scanners would flag.
 */
class ValueClassifierTest {

    /**
     * A random 32-character alphanumeric value.
     */
    private static final String RANDOM = "Hq3vX9LmP2tRw8Kz" + "N5cYb7Fd1Gs4Ja6U";

    @ParameterizedTest
    @ValueSource(strings = {
        "$(DB_PASSWORD)", "#{vault.payments.token}", "#{vault.payments.token", "@{payments.password}",
        "~{payments.api-secret}~", "~{payments.api-secret", "%(payments_password)s", "<%=ENV['X']%>",
        "${db.password}", "$(VAR)", "(VAR)", "<your-key>" })
    void references(final String value) {
        assertThat(ValueClassifier.isReference(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{Hq3vX9LmP2tRw8KzN5cYb7Fd}", "#{a b}", "Zq8vLm3NpR7tXw2Y", "p@ss{word", "#Secret123#", "" })
    void not_references(final String value) {
        assertThat(ValueClassifier.isReference(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "AAAAAAAAAAAAAAAA", "abcabcabc", "0123456789abcdef0123456789abcdef", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
        "qwertyuiopasdfghjkl", "abc123", "zyxwvu987", "Pa55Pa55Pa55" })
    void predictable(final String value) {
        assertThat(ValueClassifier.isPredictable(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Zq8vLm3NpR7tXw2Y", "password1", "hunter2hunter", "abc", "Q7RT2WXZ8VLM3NPK" })
    void not_predictable(final String value) {
        assertThat(ValueClassifier.isPredictable(value)).isFalse();
    }

    @Test
    void random_value_is_neither_reference_nor_predictable() {
        assertThat(ValueClassifier.isReference(RANDOM)).isFalse();
        assertThat(ValueClassifier.isPredictable(RANDOM)).isFalse();
        assertThat(ValueClassifier.isNotAValue(RANDOM, "app.jwt.secret=" + RANDOM)).isFalse();
    }

    @Test
    void code_and_format_descriptions_are_not_values() {
        assertThat(ValueClassifier.isNotAValue("responseHeader.split(\\\"",
            "\"var token = responseHeader.split(\\\" \\\")[1].trim();\"")).isTrue();
        assertThat(ValueClassifier.isNotAValue("x)[1]", "token=x)[1]")).isTrue();
        assertThat(ValueClassifier.isNotAValue("schema:role",
            "#hostname:port:database:username:password:schema:role")).isTrue();
    }

    @Test
    void credentials_are_values() {
        // Two words are a user and a password, not a format description.
        assertThat(ValueClassifier.isNotAValue("admin:hunter", "postgres://admin:hunter@db/app")).isFalse();
        assertThat(ValueClassifier.isNotAValue("admin:secret", "password: admin:secret")).isFalse();
        assertThat(ValueClassifier.isNotAValue("Abc(123", "password=Abc(123")).isFalse();
    }
}
