/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * @see <a href=
 * "https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/requirements.md#fr-07">FR-07</a>
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
    void ignores_unknown_fields_in_findings_file(@TempDir final Path build) throws Exception {
        Files.writeString(build.resolve("facepalm-findings.json"), "[{\"patternName\":\"Private Key Block\","
            + "\"finalSeverity\":\"ERROR\",\"finalScore\":92.0,\"maskedSecret\":\"----...----\","
            + "\"relativePath\":\"src/a.properties\"}]");

        final var html = render(build);

        assertTrue(html.contains("Critical Findings: </b>1"), html);
        assertTrue(html.contains("Private Key Block"), html);
    }

    /**
     * Issue #228: an empty findings file renders like a missing one instead of failing the whole site.
     */
    @Test
    void empty_findings_file_renders_placeholder(@TempDir final Path build) throws Exception {
        Files.writeString(build.resolve("facepalm-findings.json"), " \n");

        assertTrue(render(build).contains("No scan data available"));
    }

    /**
     * Issue #228: the Location column shows the path relative to the project, not the build machine's absolute path.
     */
    @Test
    void location_is_relative_to_the_project(@TempDir final Path build) throws Exception {
        final var file = build.resolve("src/main/resources/app.properties").toAbsolutePath();
        writeFindings(build, List.of(FindingReport.builder().patternName("Private Key Block").finalSeverity("ERROR")
            .finalScore(92.0).maskedSecret("----...----").fileAbsolutePath(file.toString()).lineNumber(3).build()));

        final var html = render(build);

        assertTrue(html.contains("src/main/resources/app.properties (Line: 3)"), html);
        assertFalse(html.contains(build.toAbsolutePath().toString()), html);
    }

    /**
     * Issue #228: severities are shown as in {@code facepalm-findings.json}, and INFO findings are counted.
     */
    @Test
    void severities_are_shown_and_counted_consistently(@TempDir final Path build) throws Exception {
        writeFindings(build, List.of(
            finding("Private Key Block", "ERROR", 92.0, "----...----"),
            finding("Generic Password Assignment", "INFO", 20.0, "5baa...8fd8")));

        final var html = render(build);

        assertFalse(html.contains("HIGH"), html);
        assertTrue(html.contains("<b>ERROR</b>"), html);
        assertTrue(html.contains("Info: </b>1"), html);
        assertFalse(html.contains("verify phase"), html);
    }

    @Test
    void corrupt_findings_file_fails_with_its_path(@TempDir final Path build) throws Exception {
        final var file = build.resolve("facepalm-findings.json");
        Files.writeString(file, "{not json");

        final var e = assertThrows(MavenReportException.class, () -> render(build));
        assertTrue(e.getMessage().contains(file.toAbsolutePath().toString()), e.getMessage());
    }

    /**
     * Issue #38: {@code facepalm.skip} leaves the report out of the site and skips {@code mvn facepalm:report}.
     */
    @Test
    void skip_disables_the_report() throws Exception {
        final var mojo = new FacepalmReportMojo();
        assertTrue(mojo.canGenerateReport());

        set(FacepalmReportMojo.class, mojo, "skip", true);

        assertFalse(mojo.canGenerateReport());
    }

    private static String render(final Path build) throws Exception {
        final var mojo = new FacepalmReportMojo();
        set(FacepalmReportMojo.class, mojo, "buildDirectory", build.toFile());
        set(FacepalmReportMojo.class, mojo, "baseDirectory", build.toFile());
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
