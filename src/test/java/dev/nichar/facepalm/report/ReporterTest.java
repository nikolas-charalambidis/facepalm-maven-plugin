/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.FacepalmLog;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.RecordingLog;
import dev.nichar.facepalm.engine.ScanStatistics;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The HTML and SARIF reports must never contain the raw secret, and repository content must be escaped. The console
 * always shows where ERROR and WARNING findings are.
 *
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#nfr-03">NFR-03</a>
 */
class ReporterTest {

    private static final String SECRET = "ghp_s3cr3tV4lu3Th4tMustN3v3rL34k0000";

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows file names cannot contain < or >")
    void html_report_masks_secret_and_escapes_repository_content(@TempDir final Path tempDir) throws Exception {
        final var reporter = reporter();
        final var report = reporter.buildReport(List.of(finding(tempDir)), new ScanStatistics(), "root", "1.0.0");

        final var html = tempDir.resolve("report.html");
        reporter.generateHtml(report, html);
        final var content = Files.readString(html);

        assertFalse(content.contains(SECRET), "raw secret in HTML");
        assertFalse(content.contains("<script>alert(1)</script>"), "unescaped snippet in HTML");
        assertFalse(content.contains("<img src=x"), "unescaped path in HTML");
        assertTrue(content.contains("&lt;script&gt;"), "escaped snippet expected");
    }

    @Test
    void sarif_report_masks_secret(@TempDir final Path tempDir) throws Exception {
        final var reporter = reporter();
        final var report = reporter.buildReport(List.of(finding(tempDir, 90, 90)), new ScanStatistics(), "root",
            "1.0.0");

        final var sarif = tempDir.resolve("report.sarif");
        reporter.generateSarif(report, sarif.toFile());

        assertFalse(Files.readString(sarif).contains(SECRET), "raw secret in SARIF");
    }

    /**
     * Issue #203: without showScoring, a failing scan printed no file or line.
     */
    @Test
    void console_lists_error_and_warning_findings_with_location_by_default(@TempDir final Path tempDir)
            throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = reporter(capturing(lines));
        final var error = finding(tempDir, 90, 90);
        error.log("Test adjustment", 5, -10);
        final var info = finding(tempDir, 10, 10);

        reporter.printLogs(new ScanStatistics(), List.of(error, info));

        final var location = error.getContext().getPath() + ":1";
        assertThat(lines).contains("ERROR [GitHub Token] " + location + " (ghp_...0000)");
        assertThat(lines).noneMatch(line -> line.contains("Score:"));
        assertThat(lines).noneMatch(line -> line.contains("Test adjustment"));
        assertThat(lines).filteredOn(line -> line.contains(location)).hasSize(1);
        assertThat(lines).noneMatch(line -> line.contains(SECRET));
    }

    @Test
    void console_adds_score_breakdown_with_show_scoring(@TempDir final Path tempDir) throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = reporter(capturing(lines));
        final var config = new FacepalmConfig();
        config.getScoring().setShowScoring(true);
        setContext(reporter, config);

        final var finding = finding(tempDir, 90, 90);
        finding.log("First", 5, -10);
        finding.log("Second", -5, 10);
        reporter.printLogs(new ScanStatistics(), List.of(finding));

        assertThat(lines).anyMatch(line -> line.startsWith("ERROR [GitHub Token] ")
            && line.endsWith(" (ghp_...0000) - Score: 82.8 (R:90/C:90)"));
        final var first = lines.indexOf("ERROR     - First (R:+5/C:-10)");
        assertThat(first).isPositive();
        assertThat(lines).contains("ERROR     - Second (R:-5/C:+10)");
        assertThat(lines.indexOf("ERROR     - Second (R:-5/C:+10)")).isEqualTo(first + 1);
    }

    /**
     * Issue #289: the result line and the status message follow the same decision as the build, for every combination
     * of failOnError and failOnWarnings, with critical findings and with warnings only.
     */
    @ParameterizedTest(name = "failOnError={0}, failOnWarnings={1}, critical={2}")
    @CsvSource({
        "true,  false, true,  FAILURE,  'High-risk issues detected! Action required. The build fails: failOnError is true (1 critical, 1 warnings).'",
        "false, false, true,  WARNINGS, 'High-risk issues detected! Action required. The build passes: failOnError is false (1 critical, 1 warnings).'",
        "false, true,  true,  FAILURE,  'High-risk issues detected! Action required. The build fails: failOnWarnings is true (1 critical, 1 warnings).'",
        "true,  true,  true,  FAILURE,  'High-risk issues detected! Action required. The build fails: failOnError is true (1 critical, 1 warnings).'",
        "true,  false, false, WARNINGS, 'Warnings detected. Review recommended. The build passes: failOnWarnings is false (0 critical, 1 warnings).'",
        "false, false, false, WARNINGS, 'Warnings detected. Review recommended. The build passes: failOnWarnings is false (0 critical, 1 warnings).'",
        "false, true,  false, FAILURE,  'Warnings detected. Review recommended. The build fails: failOnWarnings is true (0 critical, 1 warnings).'",
        "true,  true,  false, FAILURE,  'Warnings detected. Review recommended. The build fails: failOnWarnings is true (0 critical, 1 warnings).'"
    })
    void scan_result_matches_the_build_result(final boolean failOnError,
                                              final boolean failOnWarnings,
                                              final boolean critical,
                                              final String result,
                                              final String message,
                                              @TempDir final Path tempDir) throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = reporter(capturing(lines));
        final var config = new FacepalmConfig();
        config.getScoring().setFailOnError(failOnError);
        config.getScoring().setFailOnWarnings(failOnWarnings);
        setContext(reporter, config);
        final var findings = new ArrayList<Finding>();
        findings.add(finding(tempDir, 70, 70));
        if (critical) {
            findings.add(finding(tempDir, 90, 90));
        }

        reporter.printLogs(new ScanStatistics(), findings);

        assertThat(lines).contains("INFO SCAN RESULT : " + result, "INFO " + message);
    }

    @Test
    void scan_result_fails_for_an_incomplete_scan() throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = new Reporter(new RecordingLog(lines));
        setContext(reporter, new FacepalmConfig());

        reporter.printLogs(new ScanStatistics(), List.of(), true);

        assertThat(lines).contains("INFO SCAN RESULT : FAILURE",
            "INFO No secrets or sensitive patterns detected in scanned files. "
                + "The build fails: failOnScanError is true and some paths could not be scanned (0 critical, 0 warnings).");
    }

    @Test
    void incomplete_scan_keeps_the_findings_summary(@TempDir final Path tempDir) throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = reporter(capturing(lines));

        reporter.printLogs(new ScanStatistics(), List.of(finding(tempDir, 90, 90)), true);

        assertThat(lines).contains("INFO SCAN RESULT : FAILURE",
            "INFO High-risk issues detected! Action required. "
                + "The build fails: failOnScanError is true and some paths could not be scanned (1 critical, 0 warnings).");
    }

    @Test
    void scan_result_is_success_without_reported_findings(@TempDir final Path tempDir) throws Exception {
        final var lines = new ArrayList<String>();
        final var reporter = reporter(capturing(lines));

        reporter.printLogs(new ScanStatistics(), List.of(finding(tempDir, 10, 10)));

        assertThat(lines).contains("INFO SCAN RESULT : SUCCESS", "INFO Informational findings detected.");
    }

    private static Finding finding(final Path dir, final int risk, final int confidence) {
        final var path = dir.resolve("application.properties");
        final var line = "token=" + SECRET;
        return Finding.builder()
            .patternName("GitHub Token")
            .deduplicationHash("hash" + risk)
            .secretValue(SECRET)
            .lineNumber(1)
            .context(new FileContext(path, line, List.of(line)))
            .contextSnippet(line)
            .riskScore(risk)
            .confidenceScore(confidence)
            .build();
    }

    /**
     * Log that records "LEVEL message" lines.
     */
    private static FacepalmLog capturing(final List<String> lines) {
        return new RecordingLog(lines);
    }

    private static Finding finding(final Path dir) {
        final var path = dir.resolve("<img src=x onerror=alert(1)>.properties");
        final var line = "token=" + SECRET + " <script>alert(1)</script>";
        return Finding.builder()
            .patternName("GitHub Token")
            .deduplicationHash("hash")
            .secretValue(SECRET)
            .lineNumber(1)
            .context(new FileContext(path, line, List.of(line)))
            .contextSnippet(line)
            .riskScore(90)
            .confidenceScore(90)
            .build();
    }

    private static Reporter reporter() throws ReflectiveOperationException {
        return reporter(FacepalmLog.console(false));
    }

    private static Reporter reporter(final FacepalmLog log) throws ReflectiveOperationException {
        final var reporter = new Reporter(log);
        final var config = new FacepalmConfig();
        config.setScoring(new ScoringConfig());
        setContext(reporter, config);
        return reporter;
    }

    private static void setContext(final Reporter reporter, final FacepalmConfig config)
            throws ReflectiveOperationException {
        final Field field = Reporter.class.getDeclaredField("context");
        field.setAccessible(true);
        field.set(reporter, config);
    }
}
