/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Configuration for scanner execution and file filtering logic.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngineConfig {

    /**
     * Internal defaults for directories to ignore during discovery.
     */
    public static final Set<String> SKIP_DIRS = Set.of(".git", ".svn", ".hg", ".idea", ".vscode", "target", ".mvn",
        ".gradle",
        "node_modules", "dist", ".next", "build", "out");

    /**
     * Build output directory names. When listed in {@code skipDirs}, they are skipped only directly under a module
     * root (the scan root or a directory with {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts} or
     * {@code package.json}), because source packages often use these names (#226). Other names in {@code skipDirs}
     * are skipped at any depth.
     */
    public static final Set<String> BUILD_OUTPUT_DIRS = Set.of("target", "build", "out", "dist");

    /**
     * Thread pool size for parallel scanning. Defaults to available CPUs.
     */
    private Integer threads = Runtime.getRuntime().availableProcessors();

    /**
     * Safety limit for file size to prevent memory exhaustion during analysis.
     */
    @Getter
    private long maxFileSizeBytes = 5 * 1024 * 1024;

    /**
     * Filter for non-text assets and binary artifacts.
     */
    @Getter
    private String skipBinaryRegex = ".*\\.(png|jpg|jpeg|gif|pdf|zip|jar|class|tar|gz|exe|dll)$";

    /**
     * User-defined directory exclusions.
     */
    private Set<String> skipDirs;

    /**
     * Whether {@code skipDirs} adds to {@link #SKIP_DIRS} ({@code true}) or replaces it ({@code false}). {@code null}
     * (not set) keeps the 1.0.x behavior: a configured {@code skipDirs} replaces the defaults (#258).
     */
    private Boolean useDefaultSkipDirs;

    /**
     * Enable verbose logging for successfully processed files.
     */
    @Getter
    private boolean showProcessed = false;

    /**
     * Enable verbose logging for files skipped by filters.
     */
    @Getter
    private boolean showSkipped = false;

    /**
     * Honor inline {@code facepalm:ignore} and {@code facepalm:ignore-next-line} markers (#12). {@code false} reports
     * every finding as if no marker existed, for audits.
     */
    @Getter
    private boolean inlineSuppression = true;

    /**
     * Returns the configured thread count or defaults to available processors.
     */
    public Integer getThreads() {
        return threads != null ? threads : Runtime.getRuntime().availableProcessors();
    }

    /**
     * Returns the effective skip directories (#258). Maven injects an empty set when {@code skipDirs} is not
     * configured, so empty means not set.
     * <ul>
     * <li>{@code useDefaultSkipDirs} not set: {@code skipDirs} if set, otherwise the defaults (1.0.x behavior).</li>
     * <li>{@code true}: the defaults plus {@code skipDirs}.</li>
     * <li>{@code false}: {@code skipDirs} only, possibly nothing.</li>
     * </ul>
     */
    public Set<String> getSkipDirs() {
        final var configured = skipDirs != null && !skipDirs.isEmpty();
        if (useDefaultSkipDirs == null) {
            return configured ? skipDirs : SKIP_DIRS;
        }
        if (!useDefaultSkipDirs) {
            return configured ? skipDirs : Set.of();
        }
        if (!configured) {
            return SKIP_DIRS;
        }
        final Set<String> union = new HashSet<>(SKIP_DIRS);
        union.addAll(skipDirs);
        return Set.copyOf(union);
    }

    /**
     * Returns true if {@code skipDirs} replaces the defaults only because {@code useDefaultSkipDirs} is not set. This
     * is deprecated: in 2.0.0, {@code skipDirs} adds to the defaults (#259).
     */
    public boolean isSkipDirsReplacingDefaults() {
        return useDefaultSkipDirs == null && skipDirs != null && !skipDirs.isEmpty();
    }
}
