/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured report aggregating findings, metadata, and security rule definitions.
 * Serves as the primary data model for HTML and SARIF report generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanReport {

    /**
     * Environment details including version and execution timing.
     */
    private RunMetadata metadata;

    /**
     * Statistical overview of the scan results.
     */
    private ScanSummary summary;

    /**
     * Dictionary of security rules referenced by the findings.
     */
    private Map<String, RuleDefinition> ruleDictionary;

    /**
     * List of unique security leaks identified across the project.
     */
    private List<UniqueLeak> leaks;

    /**
     * Aggregated view of a specific secret found in one or more locations.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UniqueLeak {

        /**
         * Identifier for the primary detection pattern.
         */
        private String primaryRuleId;

        /**
         * Identifiers for additional rules that flagged this secret.
         */
        private List<String> supplementalRuleIds;

        /**
         * Risk score (0-100) based on pattern and context.
         */
        private int totalRisk;

        /**
         * Confidence score (0-100) reflecting detection reliability.
         */
        private int totalConfidence;

        /**
         * Composite threat level calculated from risk and confidence.
         */
        private double aggregateScore;

        /**
         * Masked secret value; the raw value is never part of the report model.
         */
        private String maskedSecret;

        /**
         * Unique fingerprint for tracking this leak across scan iterations.
         */
        private String hash;

        /**
         * Locations within the project where this secret was detected.
         */
        private List<Occurrence> occurrences;

        /**
         * Historical log of score adjustments during the evaluation phase.
         */
        private List<String> scoreHistory;
    }

    /**
     * Detailed metadata for a specific security rule.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleDefinition {

        private String id;

        private String name;

        private String description;

        /**
         * Guidance for rotating or invalidating the compromised secret.
         */
        private String remediation;

        /**
         * Classification tags for filtering (e.g., "cloud", "aws").
         */
        private List<String> tags;

        private String severity;
    }

    /**
     * Represents a specific detection within a file.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Occurrence {

        /**
         * File path relative to the project root.
         */
        private String relativePath;

        /**
         * Full absolute system path for local developer triage.
         */
        private String absolutePath;

        private int lineNumber;

        /**
         * Code snippet surrounding the detection to provide immediate context.
         */
        private String snippet;
    }

    /**
     * Metadata describing the scan execution environment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunMetadata {

        private String scannerVersion;

        private Instant timestamp;

        private long durationMs;

        private String rootPath;
    }

    /**
     * Quantitative summary of the scan results.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanSummary {

        /**
         * Count of unique secrets discovered.
         */
        private int totalLeaksFound;

        /**
         * Cumulative count of every secret occurrence found.
         */
        private int totalOccurrences;

        private int filesScanned;

        private int criticalCount;

        private int warningCount;
    }
}
