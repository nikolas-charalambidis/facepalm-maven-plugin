/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexSecretExtractorTest {

    /**
     * PEM boundary dashes, kept out of literal key headers.
     */
    private static final String DASHES = "-----";

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
            assertThat(finding.getContextSnippet()).isEqualTo("db.password=\"Sup3rS3cretValue1\"");
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
