/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import jakarta.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import lombok.Data;

/**
 * Thread-safe metrics container for real-time scan monitoring.
 * Tracks file discovery, scan progress, and exclusion reasons across parallel threads.
 */
@Data
public class ScanStatistics {

    private final long startTimeMillis = System.currentTimeMillis();

    /**
     * Files discovered during project traversal.
     */
    private final LongAdder filesDiscovered = new LongAdder();

    /**
     * Files that passed filters and were successfully analyzed.
     */
    private final LongAdder filesScanned = new LongAdder();

    /**
     * Distribution of scanned files by extension.
     */
    private final Map<String, LongAdder> suffixCounts = new ConcurrentHashMap<>();

    /**
     * Frequency of file exclusions grouped by reason.
     */
    private final Map<ExclusionReason, LongAdder> exclusionBreakdown = new ConcurrentHashMap<>();

    /**
     * Increments the total file discovery count.
     */
    public void recordDiscovery() {
        filesDiscovered.increment();
    }

    /**
     * Logs a successful file scan and tracks its extension for reporting.
     */
    public void recordScan(@Nonnull final Path path) {
        filesScanned.increment();
        final var fileName = path.getFileName().toString();
        final var lastDot = fileName.lastIndexOf('.');
        final var suffix = (lastDot == -1) ? "no-extension" : fileName.substring(lastDot).toLowerCase(Locale.ROOT);
        suffixCounts.computeIfAbsent(suffix, k -> new LongAdder()).increment();
    }

    /**
     * Logs a file exclusion and the associated reason.
     */
    public void recordExclusion(@Nonnull final ExclusionReason reason) {
        exclusionBreakdown.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    /**
     * Returns the total execution time in milliseconds.
     */
    public long getDuration() {
        return System.currentTimeMillis() - startTimeMillis;
    }
}
