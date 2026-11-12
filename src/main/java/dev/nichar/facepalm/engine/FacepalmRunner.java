/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.report.FindingReport;
import dev.nichar.facepalm.report.Reporter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

/**
 * Orchestrates the scanning lifecycle.
 * Manages gitignore discovery, extraction, scoring, and report serialization.
 */
@Named
@Singleton
public class FacepalmRunner {

    @Inject
    private @Nullable Log log;

    @Inject
    private FacepalmConfig context;

    @Inject
    private ScannerEngine engine;

    @Inject
    private GitIgnoreService gitIgnoreService;

    @Inject
    private Reporter reporter;

    private Log getLog() {
        return log != null ? log : new SystemStreamLog();
    }

    /**
     * Executes the end-to-end scanning workflow.
     * Evaluates build failure conditions based on discovery results.
     *
     * @param root Base directory to scan.
     * @param outputDir Target directory for findings and reports.
     * @param version Scanner version for metadata.
     */
    public void run(@Nonnull final Path root,
                    @Nonnull final Path outputDir,
                    @Nonnull final String version) throws Exception {

        getLog().info("Scanning " + root);

        gitIgnoreService.loadAllGitIgnores(root);
        final var findings = engine.scan(root);
        final var stats = engine.getStats();

        reporter.printLogs(stats, findings);

        // Persist findings for downstream report generation.
        final var resultsFile = new File(outputDir.toFile(), "facepalm-findings.json");
        saveFindingsToJson(findings, resultsFile);

        // Enforce build failure policies based on finding severity.
        final var scoring = context.getScoring();
        final long errors = findings.stream().filter(f -> f.getSeverity(scoring) == Severity.ERROR).count();
        final long warnings = findings.stream().filter(f -> f.getSeverity(scoring) == Severity.WARNING).count();
        final var failure = failureMessage(scoring, errors, warnings);
        if (failure != null) {
            throw new MojoFailureException(failure);
        }
    }

    /**
     * Serializes findings to a machine-readable JSON format for the reporting phase.
     */
    private void saveFindingsToJson(@Nonnull final List<Finding> findings, @Nonnull final File outputFile)
            throws Exception {
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }

        final var findingsDto = findings.stream()
            .map(finding -> mapToDto(finding, context.getScoring()))
            .toList();

        final var mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, findingsDto);
    }

    /**
     * Maps a raw scan finding to a serializable DTO.
     */
    @Nonnull
    public FindingReport mapToDto(@Nonnull final Finding finding, @Nonnull final ScoringConfig config) {
        return FindingReport.builder()
            .patternName(finding.getPatternName())
            .fileAbsolutePath(finding.getContext().getPath().toAbsolutePath().toString())
            .lineNumber(finding.getLineNumber())
            .maskedSecret(finding.getMaskedSecret())
            .contextSnippet(finding.getMaskedSnippet())
            .finalScore(finding.getNumericScore(config))
            .finalSeverity(finding.getSeverity(config).name())
            .riskScore(finding.getRiskScore())
            .confidenceScore(finding.getConfidenceScore())
            .build();
    }

    /**
     * Decides whether the build fails for the given finding counts.
     * {@code failOnError} fails on findings at the error threshold; {@code failOnWarnings} fails on any finding at the
     * warning threshold or above, so critical findings count too, independent of {@code failOnError}.
     *
     * @param scoring Failure flags.
     * @param errors Number of findings with severity {@link Severity#ERROR}.
     * @param warnings Number of findings with severity {@link Severity#WARNING}.
     * @return The failure message, or {@code null} if the build passes.
     */
    @Nullable
    static String failureMessage(@Nonnull final ScoringConfig scoring, final long errors, final long warnings) {
        if (scoring.isFailOnError() && errors > 0) {
            return "Facepalm scan failed: " + errors + " critical findings detected.";
        }
        final long atOrAboveWarning = errors + warnings;
        if (scoring.isFailOnWarnings() && atOrAboveWarning > 0) {
            return "Facepalm scan failed: " + atOrAboveWarning + " findings at or above the warning threshold ("
                + errors + " critical, " + warnings + " warnings) and failOnWarnings is true.";
        }
        return null;
    }
}
