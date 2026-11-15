/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.FacepalmRunner;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #218: no raw secret may appear in the JSON findings, in particular in the context snippet. Tokens are
 * assembled at runtime, so this file contains no literal token that other secret scanners would flag.
 */
class SnippetMaskingTest {

    /**
     * Random-looking secret used in assignments.
     */
    private static final String SECRET = "Zq8vLm3NpQ7rT2wX";

    /**
     * Shortest part of a secret that must not appear in the JSON: the mask reveals at most 4 characters per end.
     */
    private static final int LEAK_WINDOW = 5;

    @Test
    void masks_secret_followed_by_semicolon() {
        final var findings = extract("String password = \"" + SECRET + "\";");

        assertThat(findings).extracting(Finding::getSecretValue).containsExactly(SECRET);
        assertNoLeak(findings, SECRET);
    }

    @Test
    void masks_secret_followed_by_comma_in_json() {
        final var findings = extract("  \"password\": \"" + SECRET + "\",");

        assertThat(findings).extracting(Finding::getSecretValue).containsExactly(SECRET);
        assertNoLeak(findings, SECRET);
    }

    @Test
    void masks_secret_containing_doubled_quotes() {
        final var findings = extract("password='it''s-" + SECRET + "'");

        assertThat(findings).isNotEmpty();
        assertNoLeak(findings, SECRET);
    }

    @Test
    void masks_secret_containing_escaped_quote() {
        final var findings = extract("secret = \"abc\\\"" + SECRET + "\"");

        assertThat(findings).isNotEmpty();
        assertNoLeak(findings, SECRET);
    }

    @Test
    void reports_and_masks_whole_azure_storage_account_key() {
        // A real storage account key: 64 bytes, so 86 base64 characters and "==".
        final var key = "Kd7Qp2Lx9Vb4Nm6Rt1Ws8Yc3Hf5Jg0Ze" + "+/" + "Ua2Ob9Ic4Ed7Gh1Kl3Mn5Pq8Rs0Tv6Wx"
            + "Ya3Zb5Ac7Bd9Ce2Df4Eg" + "==";
        final var findings = extract(
            "azure.storage=DefaultEndpointsProtocol=https;AccountName=azurefacepalm;AccountKey="
                + key + ";EndpointSuffix=core.windows.net");

        assertThat(findings).extracting(Finding::getSecretValue).contains(key);
        assertNoLeak(findings, key);
    }

    @Test
    void reports_and_masks_whole_long_slack_token() {
        final var token = "xoxb-" + "1234567890-1234567890123-" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5Nm7";
        final var findings = extract("slack: " + token);

        assertThat(findings).extracting(Finding::getSecretValue).contains(token);
        assertNoLeak(findings, token);
    }

    @Test
    void masks_undetected_secret_next_to_detected_one() {
        final var keyId = "AKIA" + "Q7RT2WXZ8VLM3NPK";
        final var awsSecret = "Jx7Kp2Lq9Vr4Nm6St1Wu8Yz3Hb5Gc0Fd" + "AbCd1234";
        final var findings = extract("final var client = new Client(\"" + keyId + "\", \"" + awsSecret + "\");");

        assertThat(findings).extracting(Finding::getSecretValue).contains(keyId);
        assertNoLeak(findings, keyId);
        assertNoLeak(findings, awsSecret);
    }

    @Test
    void masks_secret_whose_finding_is_a_duplicate_in_another_snippet() {
        final var token = "ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5";
        final var findings = extract("db.password=" + SECRET, "backup.password=" + SECRET + " github=" + token);

        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);
        assertNoLeak(findings, SECRET);
        assertNoLeak(findings, token);
    }

    @Test
    void keeps_words_identifiers_and_versions_readable() {
        final var line = "spring-boot-starter-web version=3.5.4 password=${DB_PASSWORD}";

        assertThat(SnippetMasker.mask(line, List.of())).isEqualTo(line);
    }

    @Test
    void trims_trailing_delimiters_but_keeps_balanced_parentheses() {
        assertThat(RegexSecretExtractor.trimTrailingDelimiters(SECRET + ";")).isEqualTo(SECRET);
        assertThat(RegexSecretExtractor.trimTrailingDelimiters(SECRET + ",;")).isEqualTo(SECRET);
        assertThat(RegexSecretExtractor.trimTrailingDelimiters(SECRET + ");")).isEqualTo(SECRET);
        assertThat(RegexSecretExtractor.trimTrailingDelimiters("f(" + SECRET + ")")).isEqualTo("f(" + SECRET + ")");
    }

    /**
     * Issue #276: short or special-character values on the line of a finding were written raw, because only captures
     * and long mixed tokens were masked.
     */
    @Test
    void masks_short_undetected_values_on_the_line() {
        final var token = "ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5";
        final var findings = extract("client.connect(\"" + token + "\", \"lqyh!huu#xsef\", \"wjgvy-tjngr-5260\");");

        assertThat(findings).isNotEmpty();
        assertNoLeak(findings, token);
        assertNoLeak(findings, "lqyh!huu#xsef");
        assertNoLeak(findings, "wjgvy-tjngr-5260");
    }

    /**
     * Issue #276: on minified JSON a generic capture ran from the token into the password of a later value, so its
     * masked form showed the end of the password.
     */
    @Test
    void capture_does_not_run_into_the_next_quoted_value() {
        final var token = "ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5";
        final var password = "cvaq!igc#dhdu";
        final var findings = extract("{\"token\":\"" + token + "\",\"mailer\":{\"user\":\"ops\",\"pass\":\"" + password
            + "\"}}");

        assertThat(findings).isNotEmpty()
            .allSatisfy(f -> assertThat(f.getSecretValue()).doesNotContain(",").doesNotContain("mailer"));
        assertThat(findings).extracting(Finding::getMaskedSecret).noneMatch(masked -> masked.endsWith("dhdu"));
        assertNoLeak(findings, password);
    }

    /**
     * Issue #276: the snippet was the whole line, so every finding on a minified file carried the entire file.
     */
    @Test
    void snippet_of_a_long_line_is_a_bounded_window() {
        final var token = "ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5";
        final var filler = "var a=1;".repeat(100_000);
        final var findings = extract(filler + "var t=\"" + token + "\";" + filler);

        assertThat(findings).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getContextSnippet()).hasSizeLessThan(200).startsWith("...").endsWith("...");
            assertThat(f.getContextSnippet()).contains("var t=");
        });
    }

    private static List<Finding> extract(final String... lines) {
        final var extractor = TestContainer.create(new FacepalmConfig()).getInstance(RegexSecretExtractor.class);
        return extractor.extract(new FileContext(Path.of("App.java"), String.join("\n", lines), List.of(lines)));
    }

    /**
     * Serializes the findings as {@code facepalm-findings.json} does and asserts that no part of the secret longer
     * than the mask reveals appears in it.
     */
    private static void assertNoLeak(final List<Finding> findings, final String secret) {
        final var config = new FacepalmConfig();
        final var runner = TestContainer.create(config).getInstance(FacepalmRunner.class);
        final String json;
        try {
            json = new ObjectMapper().writeValueAsString(findings.stream()
                .map(finding -> runner.mapToDto(finding, config.getScoring()))
                .toList());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i + LEAK_WINDOW <= secret.length(); i++) {
            assertThat(json).as("JSON leaks part of %s", secret)
                .doesNotContain(secret.substring(i, i + LEAK_WINDOW));
        }
    }
}
