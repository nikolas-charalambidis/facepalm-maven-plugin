/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Issue #211: patterns match on the raw line instead of the line with quotes removed. A secret is detected with the
 * same value in every notation it was detected in before, and quoted names of a secret are not reported. Values are
 * generated at runtime, so this file contains no literal token that secret scanners would flag.
 */
class RawLineMatchingTest {

    /**
     * Characters of generated values.
     */
    private static final String ALPHANUMERIC = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    /**
     * A random value long enough for every generic pattern.
     */
    private static final String VALUE = random(new Random(211), ALPHANUMERIC, 24);

    /**
     * Notations of a secret assigned to {@code password}, each detected with exactly {@link #VALUE}.
     */
    static Stream<Arguments> notations() {
        return Stream.of(
            "password=%s",
            "password = %s",
            "password: %s",
            "password=\"%s\"",
            "password='%s'",
            "password = \"%s\";",
            "password: \"%s\"",
            "  password: '%s'",
            "\"password\": \"%s\"",
            "\"password\":\"%s\",",
            "'password': '%s'",
            "'password': %s",
            "{\"password\":\"%s\",\"user\":\"admin\"}",
            "call(password=\"%s\")",
            "call(password: \"%s\")",
            "export PASSWORD=\"%s\"",
            "password = \"\"\"%s\"\"\"",
            "password = '''%s'''",
            "password = r\"%s\"",
            "password = f'%s'",
            "password = b\"%s\"",
            "password = rb\"%s\"",
            "password = @\"%s\"",
            "password = $\"%s\"",
            "password = r#\"%s\"#",
            "<password>%s</password>",
            "<property name=\"password\" value=\"%s\"/>",
            "<property name=password value=%s/>")
            .map(form -> Arguments.argumentSet(form, form.replace("%s", VALUE)));
    }

    @ParameterizedTest
    @MethodSource("notations")
    void secret_is_detected_with_its_value_in_every_notation(final String line) {
        assertThat(extract(line)).singleElement().satisfies(finding -> {
            assertThat(finding.getPatternName()).isEqualTo("Generic Password Assignment");
            assertThat(finding.getSecretValue()).isEqualTo(VALUE);
            assertThat(finding.getContextSnippet()).doesNotContain(VALUE);
        });
    }

    /**
     * Calls and placeholders that only name a secret, with the name quoted. They were ignored because quotes were
     * stripped; matching on the raw line must not report them.
     */
    static Stream<Arguments> references() {
        return Stream.of(
            "token = getenv(\"GITHUB_TOKEN\")",
            "token = os.getenv('API_KEY')",
            "token = env.getProperty(\"spring.datasource.password\")",
            "token = Objects.requireNonNull(System.getenv(\"GITHUB_TOKEN\"))",
            "password = System.getProperty(\"db.password\")",
            "password: \"${VAULT_PASSWORD}\"",
            "password: '{{ vault_password }}'")
            .map(line -> Arguments.argumentSet(line, line));
    }

    @ParameterizedTest
    @MethodSource("references")
    void quoted_name_of_a_secret_is_not_reported(final String line) {
        assertThat(extract(line)).isEmpty();
    }

    /**
     * A partial value cut at a quote, such as {@code config[} in {@code config["api_key"]}, was reported although it
     * is shorter than the pattern's minimum; matching on the raw line no longer reports such fragments.
     */
    @Test
    void fragment_before_a_quote_is_not_reported() {
        assertThat(extract("password = config[\"api_key\"]")).isEmpty();
        assertThat(extract("password = $_ENV['DB_PASSWORD']")).isEmpty();
    }

    /**
     * An escaped or doubled quote inside a quoted value belongs to the value: the secret is still detected, with its
     * raw text, and fully masked in the snippet.
     */
    @Test
    void escaped_and_doubled_quotes_stay_in_the_value() {
        final var escaped = "ab\\\"" + VALUE;
        final var doubled = "ab''" + VALUE;

        assertThat(extract("password = \"" + escaped + "\";")).singleElement()
            .satisfies(finding -> {
                assertThat(finding.getSecretValue()).isEqualTo(escaped);
                assertThat(finding.getContextSnippet()).doesNotContain(VALUE);
            });
        assertThat(extract("password = '" + doubled + "'")).singleElement()
            .extracting(Finding::getSecretValue).isEqualTo(doubled);
    }

    /**
     * Keyword patterns of specific providers accept a quoted key and value, as in JSON.
     */
    @Test
    void specific_patterns_match_quoted_keys_and_values() {
        final var random = new Random(2110);
        final var awsSecret = random(random, ALPHANUMERIC + "+/", 40);
        final var basic = random(random, ALPHANUMERIC, 32);

        assertThat(extract("\"aws_secret_access_key\": \"" + awsSecret + "\"")).singleElement()
            .satisfies(finding -> {
                assertThat(finding.getPatternName()).isEqualTo("AWS Secret Key");
                assertThat(finding.getSecretValue()).isEqualTo(awsSecret);
            });
        assertThat(extract("\"Authorization\": \"Basic " + basic + "\"")).singleElement()
            .satisfies(finding -> {
                assertThat(finding.getPatternName()).isEqualTo("Basic Auth Header");
                assertThat(finding.getSecretValue()).isEqualTo(basic);
            });
    }

    /**
     * A value with many escapes must not overflow the stack: the value group repeats once per escape (#243).
     */
    @Test
    void value_with_many_escapes_does_not_overflow_the_stack() {
        final var line = "password = \"" + "a\\\"".repeat(50_000) + "\"";

        assertThat(extract(line)).singleElement().extracting(Finding::getPatternName)
            .isEqualTo("Generic Password Assignment");
    }

    private static List<Finding> extract(final String line) {
        final var extractor = TestContainer.create(new FacepalmConfig()).getInstance(RegexSecretExtractor.class);
        return extractor.extract(new FileContext(Path.of("app.properties"), line, List.of(line)));
    }

    private static String random(final Random random, final String alphabet, final int length) {
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
