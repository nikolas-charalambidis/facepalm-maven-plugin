/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.report.FindingReport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.maven.doxia.module.xhtml5.Xhtml5SinkFactory;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rendering branches of the {@code report} goal.
 */
class FacepalmReportMojoTest {

    @Test
    void renders_findings_sorted_by_score(@TempDir final Path build) throws Exception {
        writeFindings(build, List.of(
            finding("Generic Password Assignment", "WARNING", 69.8, "5baa...8fd8"),
            finding("Private Key Block", "ERROR", 92.0, "----...----")));

        final var html = render(build);

        assertTrue(html.contains("Critical Findings: </b>1"), html);
        assertTrue(html.contains("Warnings: </b>1"), html);
        assertTrue(html.contains("5baa...8fd8"), html);
        assertTrue(html.indexOf("Private Key Block") < html.indexOf("Generic Password Assignment"),
            "highest score first");
    }

    @Test
    void renders_clean_message_for_empty_findings(@TempDir final Path build) throws Exception {
        writeFindings(build, List.of());

        assertTrue(render(build).contains("No security findings detected"));
    }

    @Test
    void renders_placeholder_when_scan_did_not_run(@TempDir final Path build) throws Exception {
        assertTrue(render(build).contains("No scan data available"));
    }

    @Test
    void corrupt_findings_file_fails_with_its_path(@TempDir final Path build) throws Exception {
        final var file = build.resolve("facepalm-findings.json");
        Files.writeString(file, "{not json");

        final var e = assertThrows(MavenReportException.class, () -> render(build));
        assertTrue(e.getMessage().contains(file.toAbsolutePath().toString()), e.getMessage());
    }

    private static String render(final Path build) throws Exception {
        final var mojo = new FacepalmReportMojo();
        set(FacepalmReportMojo.class, mojo, "buildDirectory", build.toFile());
        set(AbstractMavenReport.class, mojo, "outputDirectory", build.resolve("site").toFile());
        final var out = new ByteArrayOutputStream();
        final var factory = new Xhtml5SinkFactory();
        mojo.generate(factory.createSink(out, "UTF-8"), factory, Locale.ROOT);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void writeFindings(final Path build, final List<FindingReport> findings) throws Exception {
        new ObjectMapper().writeValue(build.resolve("facepalm-findings.json").toFile(), findings);
    }

    private static FindingReport finding(final String pattern,
                                         final String severity,
                                         final double score,
                                         final String masked) {
        return FindingReport.builder().patternName(pattern).finalSeverity(severity).finalScore(score)
            .maskedSecret(masked).fileAbsolutePath(new File("app.properties").getAbsolutePath()).lineNumber(1)
            .riskScore(80).confidenceScore(60).build();
    }

    private static void set(final Class<?> owner, final Object target, final String name, final Object value)
            throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
