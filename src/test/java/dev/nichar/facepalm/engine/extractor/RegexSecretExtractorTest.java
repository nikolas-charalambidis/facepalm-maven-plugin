/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RegexSecretExtractorTest {

    /**
     * PEM boundary dashes, kept out of literal key headers.
     */
    private static final String DASHES = "-----";

    /**
     * Characters of generated alphanumeric tokens.
     */
    private static final String ALPHANUMERIC = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    /**
     * Characters of generated base64 tokens.
     */
    private static final String BASE64 = ALPHANUMERIC + "+/";

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

    @Test
    void extracts_secret_with_line_number_and_without_quotes() {
        final var findings = extract("# database", "db.password=\"Sup3rS3cretValue1\"");

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.getPatternName()).isEqualTo("Generic Password Assignment");
            assertThat(finding.getSecretValue()).isEqualTo("Sup3rS3cretValue1");
            assertThat(finding.getLineNumber()).isEqualTo(2);
            assertThat(finding.getContextSnippet()).isEqualTo("db.password=\"Su...e1\"");
        });
    }

    @Test
    void reports_the_same_secret_once_per_file() {
        final var findings = extract("db.password=Sup3rS3cretValue1", "backup.password=Sup3rS3cretValue1");

        assertThat(findings).singleElement().extracting(Finding::getLineNumber).isEqualTo(1);
    }

    @Test
    void reports_multi_line_private_key_at_its_first_line() {
        final var findings = extract("# key", DASHES + "BEGIN PRIVATE KEY" + DASHES, "MIIBVQIBADANBgkqhkiG9w0BAQEFAASC",
            DASHES + "END PRIVATE KEY" + DASHES);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.getPatternName()).isEqualTo("Private Key Block");
            assertThat(finding.getLineNumber()).isEqualTo(2);
            assertThat(finding.getContextSnippet()).isEqualTo("[MULTI-LINE BLOCK]");
        });
    }

    /**
     * Issue #287: the value of an XML element or attribute is reported without the closing tag or the quotes.
     */
    @Test
    void reports_xml_element_and_attribute_values() {
        final var findings = extract("<server>", "  <password>Sup3rS3cretValue1</password>",
            "<property name=\"db.password\" value=\"An0therS3cret99\"/>", "</server>");

        assertThat(findings).extracting(Finding::getPatternName, Finding::getSecretValue, Finding::getLineNumber)
            .containsExactlyInAnyOrder(
                tuple("Generic Password Assignment", "Sup3rS3cretValue1", 2),
                tuple("Generic Password Assignment", "An0therS3cret99", 3));
    }

    @Test
    void secret_value_is_the_group_of_the_matching_alternative() {
        assertEquals("y", secret("a=(x)|b=(y)", "b=y"));
    }

    /**
     * Issue #290: a value matched by a specific pattern and by a generic assignment pattern is reported once, by the
     * specific pattern.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specificAndGenericMatches")
    void value_matched_by_two_patterns_is_reported_once_by_the_specific_one(final String patternName,
                                                                            final String line) {
        final var findings = extract(line);

        assertThat(findings).singleElement().extracting(Finding::getPatternName).isEqualTo(patternName);
    }

    static Stream<Arguments> specificAndGenericMatches() {
        final var random = new Random(290);
        return Stream.of(
            Arguments.of("AWS Secret Key", "aws_secret_access_key=" + random(random, BASE64, 40)),
            Arguments.of("Slack Token", "SLACK_BOT_TOKEN=xoxb-" + random(random, ALPHANUMERIC, 24)),
            Arguments.of("Cohere API Key", "cohere_api_key=" + random(random, ALPHANUMERIC, 40)),
            Arguments.of("GitHub Token", "token=ghp_" + random(random, ALPHANUMERIC, 36)));
    }

    /**
     * Issue #290: one value is one finding per file, even if another line matches it with a less specific pattern.
     */
    @Test
    void same_value_on_two_lines_is_reported_once_by_the_specific_pattern() {
        final var value = random(new Random(2900), BASE64, 40);

        final var findings = extract("api_key=" + value, "aws_secret_access_key=" + value);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.getPatternName()).isEqualTo("AWS Secret Key");
            assertThat(finding.getLineNumber()).isEqualTo(2);
        });
    }

    /**
     * Two different secrets on one line are both reported.
     */
    @Test
    void different_values_on_one_line_are_both_reported() {
        final var random = new Random(2901);

        final var findings = extract("token=ghp_" + random(random, ALPHANUMERIC, 36) + " db.password="
            + random(random, ALPHANUMERIC, 16));

        assertThat(findings).extracting(Finding::getPatternName)
            .containsExactlyInAnyOrder("GitHub Token", "Generic Password Assignment");
    }

    private static String random(final Random random, final String alphabet, final int length) {
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static List<Finding> extract(final String... lines) {
        final var extractor = TestContainer.create(new FacepalmConfig()).getInstance(RegexSecretExtractor.class);
        return extractor.extract(new FileContext(Path.of("app.properties"), String.join("\n", lines), List.of(lines)));
    }

    private static String secret(final String regex, final String input) {
        final var matcher = Pattern.compile(regex).matcher(input);
        assertTrue(matcher.find());
        return RegexSecretExtractor.secretValue(matcher);
    }
}
