/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.extractor.SecretExtractor;
import dev.nichar.facepalm.report.FindingReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Build failure policies for findings and incomplete scans.
 *
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#fr-02">FR-02</a>
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#fr-06">FR-06</a>
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#fr-07">FR-07</a>
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#nfr-04">NFR-04</a>
 */
class FacepalmRunnerTest {

    @Test
    void json_includes_adjustments_when_show_scoring_is_off(@TempDir final Path tempDir) throws Exception {
        final var config = new ScoringConfig();
        final var finding = Finding.builder().patternName("Test").deduplicationHash("hash")
            .secretValue("fake-value-12345678").context(new FileContext(tempDir.resolve("app.properties"), "",
                List.of())).riskScore(50).confidenceScore(40).build();
        finding.log("High Risk Configuration File", 15, 20);

        final var dto = new FacepalmRunner().mapToDto(finding, config);
        final var mapper = new ObjectMapper();
        final var json = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(config.isShowScoring()).isFalse();
        assertThat(json.get("adjustments").get(0).get("name").asText())
            .isEqualTo("High Risk Configuration File");
        assertThat(json.get("adjustments").get(0).get("risk").asInt()).isEqualTo(15);
        assertThat(json.get("adjustments").get(0).get("confidence").asInt()).isEqualTo(20);
        assertThat(mapper.readValue(json.toString(), FindingReport.class).getAdjustments())
            .containsExactly(new ScoreAdjustment("High Risk Configuration File", 15, 20));
    }

    @Test
    void no_flags_never_fail() {
        assertNull(FacepalmRunner.failureMessage(scoring(false, false), 3, 2));
    }

    @Test
    void fail_on_error_fails_only_on_errors() {
        assertEquals("Facepalm scan failed: 1 critical findings detected.",
            FacepalmRunner.failureMessage(scoring(true, false), 1, 0));
        assertNull(FacepalmRunner.failureMessage(scoring(true, false), 0, 5));
    }

    @Test
    void fail_on_warnings_counts_errors_too() {
        // Issue #66: with failOnError=false, critical findings passed while mild ones failed.
        assertEquals("Facepalm scan failed: 2 findings at or above the warning threshold "
            + "(2 critical, 0 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(false, true), 2, 0));
        assertEquals("Facepalm scan failed: 1 findings at or above the warning threshold "
            + "(0 critical, 1 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(false, true), 0, 1));
    }

    @Test
    void both_flags_report_errors_first() {
        assertEquals("Facepalm scan failed: 1 critical findings detected.",
            FacepalmRunner.failureMessage(scoring(true, true), 1, 1));
        assertEquals("Facepalm scan failed: 1 findings at or above the warning threshold "
            + "(0 critical, 1 warnings) and failOnWarnings is true.",
            FacepalmRunner.failureMessage(scoring(true, true), 0, 1));
    }

    @Test
    void no_findings_never_fail() {
        assertNull(FacepalmRunner.failureMessage(scoring(true, true), 0, 0));
    }

    @ParameterizedTest
    @CsvSource({
        "false, false, false, ERROR,",
        "true, false, false, ERROR,",
        "true, true, false, ERROR, 'Facepalm scan failed: 1 critical findings detected.'",
        "true, false, true, WARNING, 'Facepalm scan failed: 1 findings at or above the warning threshold "
            + "(0 critical, 1 warnings) and failOnWarnings is true.'"
    })
    void scan_failure_includes_each_enabled_reason(final boolean strict,
                                                   final boolean failOnError,
                                                   final boolean failOnWarnings,
                                                   final Severity severity,
                                                   final String findingFailure,
                                                   @TempDir final Path tempDir) throws Exception {
        final var root = Files.createDirectory(tempDir.resolve("project"));
        for (final var name : List.of("z.properties", "a.properties", "good.properties", "skip.png")) {
            Files.writeString(root.resolve(name), "name=value\n");
        }
        Files.writeString(root.resolve("large.txt"), "x".repeat(32));
        Files.writeString(Files.createDirectory(root.resolve("target")).resolve("ignored.txt"), "name=value\n");
        final var config = new FacepalmConfig();
        config.getEngine().setFailOnScanError(strict);
        config.getEngine().setThreads(2);
        config.getEngine().setMaxFileSizeBytes(16);
        config.setScoring(scoring(failOnError, failOnWarnings));
        final var container = TestContainer.create(config);
        final SecretExtractor extractor = file -> {
            if (file.getPath().endsWith("a.properties")) {
                throw new IllegalStateException("test processing failure");
            }
            if (file.getPath().endsWith("z.properties")) {
                throw new AssertionError("test worker failure");
            }
            return List.of(Finding.builder().patternName("Test finding").deduplicationHash("test-finding")
                .secretValue("synthetic-value").context(file).lineNumber(1).fixedSeverity(severity).build());
        };
        final var extractorField = ScannerEngine.class.getDeclaredField("extractors");
        extractorField.setAccessible(true);
        extractorField.set(container.getInstance(ScannerEngine.class), List.of(extractor));

        final var runner = container.getInstance(FacepalmRunner.class);
        final var output = tempDir.resolve("out");
        if (strict) {
            final var failure = assertThrows(ScanFailedException.class, () -> runner.run(root, output, "test"));
            final var expected = new ArrayList<String>();
            if (findingFailure != null) {
                expected.add(findingFailure);
            }
            expected.addAll(List.of(
                "Facepalm scan failed because failOnScanError is true. Could not scan 2 path(s):",
                "a.properties", "z.properties"));
            assertThat(failure.getMessage().lines()).containsExactlyElementsOf(expected);
        } else {
            assertDoesNotThrow(() -> runner.run(root, output, "test"));
        }
        final var mapper = new ObjectMapper();
        final var findings = mapper.readTree(output.resolve("facepalm-findings.json").toFile());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).get("finalSeverity").asText()).isEqualTo(severity.name());
        final var stats = mapper.readTree(output.resolve("facepalm-statistics.json").toFile());
        assertThat(stats.get("filesScanned").asLong()).isEqualTo(1L);
        assertThat(stats.get("filesSkipped").asLong()).isEqualTo(4L);
    }

    /**
     * Issue #228: a scan that aborts must not leave the results of an earlier run, which the report would render as
     * current.
     */
    @Test
    void aborted_scan_leaves_no_results_of_an_earlier_run(@TempDir final Path tempDir) throws Exception {
        final var root = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(root.resolve("app.properties"), "name=value\n");
        final var output = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(output.resolve("facepalm-findings.json"), "[]");
        Files.writeString(output.resolve("facepalm-statistics.json"), "{}");
        final var config = new FacepalmConfig();
        // An invalid regex aborts the scan before any result is written.
        config.getEngine().setSkipBinaryRegex("(");

        assertThrows(PatternSyntaxException.class,
            () -> TestContainer.create(config).getInstance(FacepalmRunner.class).run(root, output, "test"));

        assertFalse(Files.exists(output.resolve("facepalm-findings.json")));
        assertFalse(Files.exists(output.resolve("facepalm-statistics.json")));
    }

    private static ScoringConfig scoring(final boolean failOnError, final boolean failOnWarnings) {
        final var scoring = new ScoringConfig();
        scoring.setFailOnError(failOnError);
        scoring.setFailOnWarnings(failOnWarnings);
        return scoring;
    }
}
