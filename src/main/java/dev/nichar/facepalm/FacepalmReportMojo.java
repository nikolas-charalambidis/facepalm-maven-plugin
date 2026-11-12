/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.report.FindingReport;
import jakarta.annotation.Nonnull;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;

/**
 * Maven Site reporting Mojo for integrated security scan results.
 * Visualizes the JSON findings produced during the 'scan' phase.
 */
@Mojo(name = "report", defaultPhase = LifecyclePhase.SITE, threadSafe = true)
public class FacepalmReportMojo extends AbstractMavenReport {

    /**
     * Build directory where the scan goal writes {@code facepalm-findings.json}.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDirectory;

    /**
     * Unique identifier for the report in Maven Site navigation.
     */
    @Override
    public String getOutputName() {
        return "facepalm-report";
    }

    /**
     * Localized report name for the Site index.
     */
    @Override
    public String getName(@Nonnull final Locale locale) {
        return "Facepalm Report";
    }

    /**
     * Localized report description.
     */
    @Override
    public String getDescription(@Nonnull final Locale locale) {
        return "Scan results for leaked secrets.";
    }

    /**
     * Generates the Maven Site report by deserializing scan findings.
     * Translates raw JSON data into structured Doxia Sink elements.
     */
    @Override
    protected void executeReport(@Nonnull final Locale locale) throws MavenReportException {
        getLog().debug("Facepalm report generation output directory: " + outputDirectory.getAbsolutePath());

        // The scan goal writes the findings into the build directory, independent of the reporting directory.
        final var resultsFile = new File(buildDirectory, "facepalm-findings.json");

        if (!resultsFile.exists()) {
            getLog().warn("Facepalm results not found at " + resultsFile.getAbsolutePath() +
                ". Please run 'mvn verify' (or 'mvn facepalm:scan') before generating the site report.");
            renderEmptyReport();
            return;
        }

        final List<FindingReport> findings;
        try {
            final var mapper = new ObjectMapper();
            // Deserialize findings from the machine-readable JSON format.
            findings = mapper.readValue(
                resultsFile,
                mapper.getTypeFactory().constructCollectionType(List.class, FindingReport.class));
        } catch (Exception e) {
            throw new MavenReportException(
                "Failed to read " + resultsFile.getAbsolutePath() + ". Is the file corrupted?", e);
        }

        // Generate the HTML content using Maven's Doxia Sink API.
        renderReport(findings);
    }

    /**
     * Renders findings into structured HTML via the Doxia Sink API.
     */
    private void renderReport(@Nonnull final List<FindingReport> findings) {
        final var sink = getSink();

        // Apply descending sort by threat score.
        findings.sort(Comparator.comparing(FindingReport::getFinalScore, Comparator.reverseOrder()));

        // Document Head Section
        sink.head();
        sink.title();
        sink.text("Facepalm Security Report");
        sink.title_();
        sink.head_();

        sink.body();

        // Page Header
        sink.section1();
        sink.sectionTitle1();
        sink.text("Facepalm Security Scan Results");
        sink.sectionTitle1_();

        sink.paragraph();
        sink.text("The following potential secrets and security risks were identified during the verify phase.");
        sink.paragraph_();

        // High-level Summary Breakdown
        final long errors = findings.stream().filter(fr -> "ERROR".equals(fr.getFinalSeverity())).count();
        final long warnings = findings.stream().filter(fr -> "WARNING".equals(fr.getFinalSeverity())).count();

        sink.list();
        sink.listItem();
        sink.bold();
        sink.text("Critical Findings: ");
        sink.bold_();
        sink.text(String.valueOf(errors));
        sink.listItem_();
        sink.listItem();
        sink.bold();
        sink.text("Warnings: ");
        sink.bold_();
        sink.text(String.valueOf(warnings));
        sink.listItem_();
        sink.list_();

        sink.horizontalRule();

        // Render detailed findings as a tabular overview.
        if (findings.isEmpty()) {
            sink.paragraph();
            sink.italic();
            sink.text("No security findings detected. Your codebase looks clean!");
            sink.italic_();
            sink.paragraph_();
        } else {
            sink.table();

            // Render Table Header
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text("Severity");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Total Score");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Risk Score");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Confidence Score");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Location");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Pattern / Secret");
            sink.tableHeaderCell_();
            sink.tableRow_();

            // Populate Table Rows
            for (final var finding : findings) {
                sink.tableRow();

                // Severity Column with High-risk emphasis
                sink.tableCell();
                // Highlight high-risk findings with bold formatting.
                if ("ERROR".equals(finding.getFinalSeverity())) {
                    sink.bold();
                    sink.text("HIGH");
                    sink.bold_();
                } else {
                    sink.text(finding.getFinalSeverity());
                }
                sink.tableCell_();

                // Final Score Column
                sink.tableCell();
                sink.bold();
                sink.text(String.format(Locale.ROOT, "%.1f", finding.getFinalScore()));
                sink.bold_();
                sink.tableCell_();

                // Risk Score Column
                sink.tableCell();
                sink.text(String.valueOf(finding.getRiskScore()));
                sink.tableCell_();

                // Confidence Score Column
                sink.tableCell();
                sink.text(String.valueOf(finding.getConfidenceScore()));
                sink.tableCell_();

                // File Path and Line Number
                sink.tableCell();
                sink.italic();
                sink.text(finding.getFileAbsolutePath() + " (Line: " + finding.getLineNumber() + ")");
                sink.italic_();
                sink.tableCell_();

                // Masked Secret and Pattern Identification
                sink.tableCell();
                sink.text(finding.getPatternName() + ": ");
                sink.monospaced();
                sink.text(finding.getMaskedSecret());
                sink.monospaced_();
                sink.tableCell_();

                sink.tableRow_();
            }
            sink.table_();
        }

        sink.section1_();
        sink.body_();

        sink.flush();
        sink.close();
    }

    /**
     * Renders a placeholder message if scan data is missing.
     */
    private void renderEmptyReport() {
        final var sink = getSink();
        sink.head();
        sink.title();
        sink.text("Facepalm Scan Results");
        sink.title_();
        sink.head_();

        sink.body();
        sink.section1();
        sink.sectionTitle1();
        sink.text("Facepalm Security Report");
        sink.sectionTitle1_();

        sink.paragraph();
        sink.text(
            "No scan data available. You must run the facepalm:scan goal prior to site generation to see results here.");
        sink.paragraph_();

        sink.section1_();
        sink.body_();
        sink.flush();
        sink.close();
    }
}
