/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.engine.ExclusionReason;
import dev.nichar.facepalm.engine.ScanStatistics;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Issue #244: scan throughput in the log and in {@code facepalm-statistics.json}.
 */
class ScanStatisticsReportTest {

    @Test
    void formats_the_log_line_with_thousands_separators_and_decimal_units() {
        final var report = ScanStatisticsReport.of(1_300, 1_234, 12, 56_700_000, TimeUnit.MILLISECONDS.toNanos(3_200));

        assertThat(report.toLogLine()).isEqualTo("Scanned 1,234 files (56.7 MB) in 3.2 s (17.7 MB/s), 12 skipped");
    }

    @Test
    void omits_the_throughput_for_a_zero_duration() {
        final var report = ScanStatisticsReport.of(3, 3, 0, 2_048, 0);

        assertThat(report.bytesPerSecond()).isNull();
        assertThat(report.toLogLine()).isEqualTo("Scanned 3 files (2.0 kB) in 0.0 s, 0 skipped");
    }

    @Test
    void formats_an_empty_scan() {
        final var report = ScanStatisticsReport.of(0, 0, 0, 0, TimeUnit.MILLISECONDS.toNanos(5));

        assertThat(report.toLogLine()).isEqualTo("Scanned 0 files (0 B) in 0.0 s (0 B/s), 0 skipped");
    }

    @Test
    void ignores_the_default_locale() {
        final var original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            final var report = ScanStatisticsReport.of(1_234, 1_234, 0, 1_500_000, TimeUnit.SECONDS.toNanos(1));

            assertThat(report.toLogLine()).isEqualTo("Scanned 1,234 files (1.5 MB) in 1.0 s (1.5 MB/s), 0 skipped");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void formats_bytes_with_decimal_units() {
        assertThat(ScanStatisticsReport.formatBytes(999)).isEqualTo("999 B");
        assertThat(ScanStatisticsReport.formatBytes(1_000)).isEqualTo("1.0 kB");
        assertThat(ScanStatisticsReport.formatBytes(56_700_000)).isEqualTo("56.7 MB");
        assertThat(ScanStatisticsReport.formatBytes(3_000_000_000L)).isEqualTo("3.0 GB");
        assertThat(ScanStatisticsReport.formatBytes(5_000_000_000_000_000L)).isEqualTo("5000.0 TB");
    }

    @Test
    void captures_and_serializes_the_statistics_of_a_scan() throws Exception {
        final var stats = new ScanStatistics();
        stats.markStarted();
        stats.recordDiscovery();
        stats.recordDiscovery();
        stats.recordScan(Path.of("app.properties"), 1_500);
        stats.recordExclusion(ExclusionReason.BINARY_FILE);
        stats.markFinished();

        final var report = ScanStatisticsReport.from(stats);
        final var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(report));

        assertThat(report.filesDiscovered()).isEqualTo(2);
        assertThat(report.filesScanned()).isEqualTo(1);
        assertThat(report.filesSkipped()).isEqualTo(1);
        assertThat(report.bytesScanned()).isEqualTo(1_500);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("filesDiscovered", "filesScanned",
            "filesSkipped", "bytesScanned", "durationMillis", "bytesPerSecond");
    }
}
