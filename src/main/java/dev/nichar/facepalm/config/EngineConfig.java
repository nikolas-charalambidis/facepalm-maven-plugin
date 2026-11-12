/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

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
     * Returns the configured thread count or defaults to available processors.
     */
    public Integer getThreads() {
        return threads != null ? threads : Runtime.getRuntime().availableProcessors();
    }

    /**
     * Returns the user-defined skip list or falls back to internal defaults.
     * Maven injects an empty set when the parameter is not configured, so empty also means defaults.
     */
    public Set<String> getSkipDirs() {
        return skipDirs != null && !skipDirs.isEmpty() ? skipDirs : SKIP_DIRS;
    }
}
