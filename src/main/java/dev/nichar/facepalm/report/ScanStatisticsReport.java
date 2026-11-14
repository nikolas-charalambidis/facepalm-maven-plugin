/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.report;

import dev.nichar.facepalm.engine.ScanStatistics;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Size and speed of one scan, logged at its end and written to {@code facepalm-statistics.json} (#244).
 *
 * @param filesDiscovered Files found below the scan root.
 * @param filesScanned Files read and analysed.
 * @param filesSkipped Files excluded by name, size, binary content or an error.
 * @param bytesScanned Total size of the scanned files in bytes.
 * @param durationMillis Duration of the scan, from discovery to the last analysed file.
 * @param bytesPerSecond Scanned bytes per second, or {@code null} if the duration is too short to measure.
 */
public record ScanStatisticsReport(long filesDiscovered, long filesScanned, long filesSkipped, long bytesScanned,
        long durationMillis, @Nullable Long bytesPerSecond) {

    /**
     * Name of the JSON file written next to {@code facepalm-findings.json}.
     */
    public static final String FILE_NAME = "facepalm-statistics.json";

    /**
     * Units of {@link #formatBytes(long)}, in steps of 1,000.
     */
    private static final String[] UNITS = {
        "kB", "MB", "GB", "TB" };

    /**
     * Factor between two {@link #UNITS}.
     */
    private static final double UNIT_STEP = 1000.0;

    /**
     * Captures the statistics of a finished scan.
     */
    @Nonnull
    public static ScanStatisticsReport from(@Nonnull final ScanStatistics stats) {
        final var nanos = stats.getDurationNanos();
        final var bytes = stats.getBytesScanned().sum();
        final var skipped = stats.getExclusionBreakdown().values().stream().mapToLong(LongAdder::sum).sum();
        return of(stats.getFilesDiscovered().sum(), stats.getFilesScanned().sum(), skipped, bytes, nanos);
    }

    /**
     * Builds the statistics from raw values; the throughput is {@code null} for a zero duration.
     */
    @Nonnull
    static ScanStatisticsReport of(final long discovered,
                                   final long scanned,
                                   final long skipped,
                                   final long bytes,
                                   final long durationNanos) {
        final Long perSecond = durationNanos > 0
            ? Math.round(bytes * (double) TimeUnit.SECONDS.toNanos(1) / durationNanos)
            : null;
        return new ScanStatisticsReport(discovered, scanned, skipped, bytes,
            TimeUnit.NANOSECONDS.toMillis(durationNanos), perSecond);
    }

    /**
     * One log line, for example {@code Scanned 1,234 files (56.7 MB) in 3.2 s (17.7 MB/s), 12 skipped}.
     */
    @Nonnull
    public String toLogLine() {
        final var throughput = bytesPerSecond != null ? " (" + formatBytes(bytesPerSecond) + "/s)" : "";
        return String.format(Locale.ROOT, "Scanned %,d files (%s) in %.1f s%s, %,d skipped", filesScanned,
            formatBytes(bytesScanned), durationMillis / 1000.0, throughput, filesSkipped);
    }

    /**
     * Formats a size with decimal units: {@code 999 B}, {@code 1.0 kB}, {@code 56.7 MB}.
     */
    @Nonnull
    static String formatBytes(final long bytes) {
        if (bytes < UNIT_STEP) {
            return bytes + " B";
        }
        double value = bytes / UNIT_STEP;
        int unit = 0;
        while (value >= UNIT_STEP && unit < UNITS.length - 1) {
            value /= UNIT_STEP;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
