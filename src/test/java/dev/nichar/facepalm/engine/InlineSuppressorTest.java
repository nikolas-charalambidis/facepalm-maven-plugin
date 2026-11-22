/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #12: inline {@code facepalm:ignore} and {@code facepalm:ignore-next-line} markers, tested end to end through
 * {@link FacepalmRunner#run}: the build decision and {@code facepalm-findings.json}. Key IDs are generated at runtime,
 * so this file contains no literal token that secret scanners would flag.
 */
class InlineSuppressorTest {

    /**
     * An AWS access key ID: an ERROR finding on its own, which fails the build by default.
     */
    private static final String KEY = "AWS_ACCESS_KEY_ID=AKIA" + random(new Random(12), 16);

    @Test
    void marker_on_the_line_suppresses_its_finding_and_the_build_passes(@TempDir final Path tempDir)
            throws Exception {
        final var findings = run(tempDir, config(), KEY + " # facepalm:ignore rotated test key");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f).containsEntry("patternName", "AWS Access Key").containsEntry("finalSeverity", "SUPPRESSED");
        });
        final var finding = scan(tempDir, config(), KEY + " # facepalm:ignore rotated test key").get(0);
        assertThat(finding.getSuppressionReason()).isEqualTo("rotated test key");
        assertThat(finding.getScoreHistory()).contains("Suppressed by facepalm:ignore: rotated test key");
    }

    @Test
    void next_line_marker_suppresses_the_finding_below_it(@TempDir final Path tempDir) throws Exception {
        final var findings = run(tempDir, config(), "# facepalm:ignore-next-line", KEY);

        assertThat(findings).extracting(f -> f.get("lineNumber"), f -> f.get("finalSeverity"))
            .containsExactly(tuple(2, "SUPPRESSED"));
    }

    @Test
    void marker_suppresses_only_its_own_line(@TempDir final Path tempDir) {
        assertThatThrownBy(() -> run(tempDir, config(), KEY, "", "# facepalm:ignore"))
            .isInstanceOf(ScanFailedException.class);

        final var findings = read(tempDir);
        assertThat(findings).extracting(f -> f.get("patternName"), f -> f.get("lineNumber"), f -> f.get(
            "finalSeverity"))
            .containsExactlyInAnyOrder(
                tuple("AWS Access Key", 1, "ERROR"),
                tuple(InlineSuppressor.UNUSED, 3, "WARNING"));
    }

    @Test
    void next_line_marker_does_not_suppress_its_own_line(@TempDir final Path tempDir) {
        assertThatThrownBy(() -> run(tempDir, config(), KEY + " # facepalm:ignore-next-line", "server.port=8080"))
            .isInstanceOf(ScanFailedException.class);

        assertThat(read(tempDir)).extracting(f -> f.get("patternName"), f -> f.get("finalSeverity"))
            .containsExactlyInAnyOrder(
                tuple("AWS Access Key", "ERROR"),
                tuple(InlineSuppressor.UNUSED, "WARNING"));
    }

    /**
     * An unused marker is a warning: it passes by default and fails with {@code failOnWarnings}, and the report shows
     * the marker itself, not a masked value.
     */
    @Test
    void unused_marker_is_a_warning(@TempDir final Path tempDir) throws Exception {
        final var findings = run(tempDir, config(), "server.port=8080 # facepalm:ignore no longer needed");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f)
            .containsEntry("patternName", InlineSuppressor.UNUSED)
            .containsEntry("finalSeverity", "WARNING")
            .containsEntry("maskedSecret", "facepalm:ignore")
            .containsEntry("contextSnippet", "facepalm:ignore no longer needed"));

        final var strict = config();
        strict.getScoring().setFailOnWarnings(true);
        assertThatThrownBy(() -> run(tempDir, strict, "server.port=8080 # facepalm:ignore no longer needed"))
            .isInstanceOf(ScanFailedException.class);
    }

    /**
     * The audit switch: with {@code suppressions=false} every finding is reported as if no marker existed, and
     * markers are not checked for use.
     */
    @Test
    void audit_switch_ignores_every_marker(@TempDir final Path tempDir) {
        final var audit = config();
        audit.getEngine().setSuppressions(false);

        assertThatThrownBy(() -> run(tempDir, audit, KEY + " # facepalm:ignore", "# facepalm:ignore"))
            .isInstanceOf(ScanFailedException.class);
        assertThat(read(tempDir)).extracting(f -> f.get("patternName"), f -> f.get("finalSeverity"))
            .containsExactly(tuple("AWS Access Key", "ERROR"));
    }

    /**
     * The marker is a whole word: a longer word that starts with it does not suppress anything.
     */
    @Test
    void longer_word_is_not_a_marker(@TempDir final Path tempDir) {
        assertThatThrownBy(() -> run(tempDir, config(), KEY + " # facepalm:ignored"))
            .isInstanceOf(ScanFailedException.class);
        assertThat(read(tempDir)).extracting(f -> f.get("finalSeverity")).containsExactly("ERROR");
    }

    /**
     * Comment syntaxes around the marker: the reason excludes separators and comment closers.
     */
    @Test
    void reason_excludes_separators_and_comment_closers() {
        assertThat(InlineSuppressor.reason("")).isNull();
        assertThat(InlineSuppressor.reason("   ")).isNull();
        assertThat(InlineSuppressor.reason(" -->")).isNull();
        assertThat(InlineSuppressor.reason(": env var name, not a secret")).isEqualTo("env var name, not a secret");
        assertThat(InlineSuppressor.reason(" -- sample key -->")).isEqualTo("sample key");
        assertThat(InlineSuppressor.reason(" test fixture */")).isEqualTo("test fixture");
        assertThat(InlineSuppressor.reason(" " + "x".repeat(500))).hasSize(200);
    }

    @Test
    void marker_works_in_xml_comments(@TempDir final Path tempDir) {
        assertThatCode(() -> run(tempDir, config(), "<!-- facepalm:ignore-next-line demo key -->", KEY))
            .doesNotThrowAnyException();
    }

    private static FacepalmConfig config() {
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        return config;
    }

    /**
     * Writes the lines to {@code src/main/resources/application.properties}, runs the scan as the Maven goal does and
     * returns the findings written to {@code facepalm-findings.json}.
     */
    private static List<Map<String, Object>> run(final Path tempDir,
                                                 final FacepalmConfig config,
                                                 final String... lines) throws Exception {
        final var root = write(tempDir, lines);
        try {
            TestContainer.create(config).getInstance(FacepalmRunner.class).run(root, tempDir.resolve("out"), "test");
        } finally {
            // The findings file is written before the build decision, also when the run fails.
            assertThat(tempDir.resolve("out/facepalm-findings.json")).exists();
        }
        return read(tempDir);
    }

    private static List<Finding> scan(final Path tempDir, final FacepalmConfig config, final String... lines)
            throws Exception {
        return TestContainer.create(config).getInstance(ScannerEngine.class).scan(write(tempDir, lines));
    }

    private static Path write(final Path tempDir, final String... lines) throws Exception {
        final var root = tempDir.resolve("project");
        final var file = root.resolve("src/main/resources/application.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
        return root;
    }

    private static List<Map<String, Object>> read(final Path tempDir) {
        try {
            return new ObjectMapper().readValue(tempDir.resolve("out/facepalm-findings.json").toFile(),
                new TypeReference<List<Map<String, Object>>>() {
                });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(final Random random, final int length) {
        final var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
