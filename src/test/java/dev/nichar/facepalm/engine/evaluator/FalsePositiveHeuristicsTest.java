/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.ScannerEngine;
import dev.nichar.facepalm.engine.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #225: heuristics that lowered or raised scores for the wrong reason. Each case runs the full pipeline on a
 * file and checks the resulting severity in both directions: harmless values drop, real tokens stay reported. Tokens
 * are assembled at runtime, so this file contains no literal token that other secret scanners would flag.
 */
class FalsePositiveHeuristicsTest {

    /**
     * Configuration with the default scoring.
     */
    private static final FacepalmConfig CONFIG = new FacepalmConfig();

    @Test
    void environment_variable_name_as_value_drops_to_info(@TempDir final Path tempDir) throws Exception {
        final var findings = scan(tempDir, ".github/workflows/deploy.yaml",
            "          server-password: CENTRAL_TOKEN_PASSWORD");

        assertThat(findings).isNotEmpty().allSatisfy(f -> assertThat(severity(f)).isEqualTo(Severity.INFO));
    }

    /**
     * {@code fake}, {@code example} and {@code sample} are dummy keywords: a discount on confidence, not a suppression
     * (see the FAQ), so the value scores lower than the same value without the keyword.
     */
    @Test
    void example_value_in_issue_template_is_discounted(@TempDir final Path tempDir) throws Exception {
        final var file = ".github/ISSUE_TEMPLATE/false_detection.yml";
        final var fake = scan(tempDir.resolve("fake"), file, "        api-key: abc_FAKE0FAKE0FAKE0FAKE");
        final var plain = scan(tempDir.resolve("plain"), file, "        api-key: abc_QWER0QWER0QWER0QWER");

        assertThat(fake).singleElement().satisfies(f -> {
            assertThat(f.getScoreHistory()).anyMatch(h -> h.startsWith("Dummy Keyword Discount"));
            assertThat(f.getNumericScore(CONFIG.getScoring()))
                .isLessThan(plain.get(0).getNumericScore(CONFIG.getScoring()));
        });
    }

    /**
     * Issue #227: the value was lower-cased but configured keywords were not, so a keyword with upper-case letters
     * never matched. Keywords are now normalized like the value: lower case, {@code -} and spaces as {@code _}.
     */
    @Test
    void configured_dummy_keywords_match_case_insensitively(@TempDir final Path tempDir) throws Exception {
        final var config = new FacepalmConfig();
        config.getEvaluators().setDummyKeywords(Set.of("Acme-Demo"));
        final var findings = scan(tempDir, config, ".github/ISSUE_TEMPLATE/false_detection.yml",
            "        api-key: abc_ACME_DEMO0QWER0QWER");

        assertThat(findings).singleElement()
            .satisfies(f -> assertThat(f.getScoreHistory()).anyMatch(h -> h.startsWith("Dummy Keyword Discount")));
    }

    /**
     * Real tokens, including upper-case ones, must not be taken for environment variable names or example values.
     */
    @Test
    void real_tokens_stay_reported(@TempDir final Path tempDir) throws Exception {
        final var lines = List.of(
            "aws.access.key.id=AKIA" + "Q7RT2WXZ8VLM3NPK",
            "github.token=ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5",
            "stripe.secret=sk_live_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8",
            "api_key=" + "0123456789ABCDEF0F1E2D3C4B5A6978",
            "secret=" + "JBSWY3DPEHPK3PXPKRUGS4ZANFZSA3TP");
        final var findings = scan(tempDir, "src/main/resources/application.properties", lines.toArray(String[]::new));

        assertThat(findings).hasSizeGreaterThanOrEqualTo(lines.size()).allSatisfy(f -> {
            assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith("Environment Variable Name"));
            assertThat(severity(f)).isIn(Severity.WARNING, Severity.ERROR);
        });
    }

    @Test
    void live_inside_a_stripe_key_or_word_is_no_production_context(@TempDir final Path tempDir) throws Exception {
        // Two blank lines apart, so neither line is in the other's context window.
        final var findings = scan(tempDir, "src/main/resources/application.properties",
            "stripe.secret=sk_live_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8", "", "",
            "deliver.password=Zq8vLm3NpQ7rT2wX");

        assertThat(findings).isNotEmpty().allSatisfy(
            f -> assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith("Production Context")));
    }

    @Test
    void example_host_is_no_mock_context(@TempDir final Path tempDir) throws Exception {
        final var findings = scan(tempDir, "src/main/resources/application.properties",
            "mail.host=smtp.example.com",
            "mail.password=Zq8vLm3NpQ7rT2wX");

        assertThat(findings).isNotEmpty().allSatisfy(
            f -> assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith("Mock Context")));
    }

    /**
     * Issue #296: Maven-encrypted values in {@code settings.xml} (Maven 3 {@code {base64}}, Maven 4
     * {@code {[key=value,...]base64}}) are reported as WARNING, not ERROR. A plain value in the same element stays an
     * ERROR, and a value with a brace at one end only is not taken for an encrypted one.
     */
    @Test
    void maven_encrypted_values_are_warnings(@TempDir final Path tempDir) throws Exception {
        final var file = "src/main/resources/settings.xml";
        final var base64 = "Hq3vX9LmP2tRw8KzN5cYb7Fd1Gs4Ja6U+Ve0Ti/=";
        final var encrypted = scan(tempDir.resolve("encrypted"), file,
            "<password>{" + base64 + "}</password>",
            "",
            "",
            "<token>{[name=master,cipher=AES/GCM/NoPadding,version=4.0]" + base64 + "}</token>");

        assertThat(encrypted).hasSize(2).allSatisfy(f -> {
            assertThat(f.getScoreHistory()).anyMatch(h -> h.startsWith("Maven Encrypted Value"));
            assertThat(severity(f)).isEqualTo(Severity.WARNING);
        });

        final var plain = scan(tempDir.resolve("plain"), file,
            "<password>Kq7vR2mXz9LwP4t</password>",
            "",
            "",
            "<token>{" + base64 + "</token>");

        assertThat(plain).hasSize(2).allSatisfy(f -> {
            assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith("Maven Encrypted Value"));
            assertThat(severity(f)).isEqualTo(Severity.ERROR);
        });
    }

    @Test
    void markers_still_match_as_whole_words() {
        assertThat(SurroundingContextEvaluator.containsWord("# example credentials", List.of("example"))).isTrue();
        assertThat(SurroundingContextEvaluator.containsWord("prod_db_password=x", List.of("prod"))).isTrue();
        assertThat(SurroundingContextEvaluator.containsWord("live-reload: on", List.of("live"))).isTrue();
        assertThat(SurroundingContextEvaluator.containsWord("deliver=x", List.of("live"))).isFalse();
        assertThat(SurroundingContextEvaluator.containsWord("smtp.example.com", List.of("example"))).isFalse();
        assertThat(SurroundingContextEvaluator.containsWord("anything", List.of())).isFalse();
    }

    private static List<Finding> scan(final Path tempDir, final String file, final String... lines) throws Exception {
        return scan(tempDir, new FacepalmConfig(), file, lines);
    }

    private static List<Finding> scan(final Path tempDir,
                                      final FacepalmConfig config,
                                      final String file,
                                      final String... lines) throws Exception {
        final var path = tempDir.resolve("project").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, String.join("\n", lines) + "\n");
        config.getEngine().setThreads(1);
        return TestContainer.create(config).getInstance(ScannerEngine.class).scan(tempDir.resolve("project"));
    }

    private static Severity severity(final Finding finding) {
        return finding.getSeverity(CONFIG.getScoring());
    }
}
