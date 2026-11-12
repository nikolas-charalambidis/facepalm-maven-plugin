/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.report;

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
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTML and SARIF reports must never contain the raw secret, and repository content must be escaped.
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
        final var reporter = new Reporter(new SystemStreamLog());
        final var config = new FacepalmConfig();
        config.setScoring(new ScoringConfig());
        final Field field = Reporter.class.getDeclaredField("context");
        field.setAccessible(true);
        field.set(reporter, config);
        return reporter;
    }
}
