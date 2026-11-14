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
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Thread-safe metrics container for real-time scan monitoring.
 * Tracks file discovery, scan progress, and exclusion reasons across parallel threads.
 */
@Data
public class ScanStatistics {

    private final long startTimeMillis = System.currentTimeMillis();

    /**
     * Monotonic time the scan started, reset by {@link #markStarted()}.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile long startNanos = System.nanoTime();

    /**
     * Monotonic time the scan finished, or {@code -1} while it runs.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile long endNanos = -1;

    /**
     * Total size of the scanned files in bytes, as read from disk.
     */
    private final LongAdder bytesScanned = new LongAdder();

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
     * Starts timing the scan.
     */
    public void markStarted() {
        startNanos = System.nanoTime();
        endNanos = -1;
    }

    /**
     * Stops timing the scan.
     */
    public void markFinished() {
        endNanos = System.nanoTime();
    }

    /**
     * Returns the scan duration in nanoseconds: until {@link #markFinished()}, or until now while the scan runs.
     */
    public long getDurationNanos() {
        final var end = endNanos;
        return (end < 0 ? System.nanoTime() : end) - startNanos;
    }

    /**
     * Logs a successful file scan and tracks its size and extension for reporting.
     *
     * @param bytes Size of the file content as read from disk.
     */
    public void recordScan(@Nonnull final Path path, final long bytes) {
        filesScanned.increment();
        bytesScanned.add(bytes);
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
