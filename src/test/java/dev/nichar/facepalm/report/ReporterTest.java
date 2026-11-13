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
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.ScanStatistics;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTML and SARIF reports must never contain the raw secret, and repository content must be escaped. The console
 * always shows where ERROR and WARNING findings are.
 */
class ReporterTest {

    private static final String SECRET = "ghp_s3cr3tV4lu3Th4tMustN3v3rL34k0000";

    @Test
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
        final var report = reporter.buildReport(List.of(finding(tempDir)), new ScanStatistics(), "root", "1.0.0");

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
        final var info = finding(tempDir, 10, 10);

        reporter.printLogs(new ScanStatistics(), List.of(error, info));

        final var location = error.getContext().getPath() + ":1";
        assertThat(lines).contains("ERROR [GitHub Token] " + location + " (ghp_...0000)");
        assertThat(lines).noneMatch(line -> line.contains("Score:"));
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

        reporter.printLogs(new ScanStatistics(), List.of(finding(tempDir, 90, 90)));

        assertThat(lines).anyMatch(line -> line.startsWith("ERROR [GitHub Token] ")
            && line.endsWith(" (ghp_...0000) - Score: 82.8 (R:90/C:90)"));
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
    private static SystemStreamLog capturing(final List<String> lines) {
        return new SystemStreamLog() {

            @Override
            public void info(final CharSequence content) {
                lines.add("INFO " + content);
            }

            @Override
            public void warn(final CharSequence content) {
                lines.add("WARN " + content);
            }

            @Override
            public void error(final CharSequence content) {
                lines.add("ERROR " + content);
            }
        };
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
        return reporter(new SystemStreamLog());
    }

    private static Reporter reporter(final SystemStreamLog log) throws ReflectiveOperationException {
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
