/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.bench;

import dev.nichar.facepalm.pattern.SecretPattern;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Every configured single-line pattern's regex on every line of the realistic corpus: the raw cost of matching,
 * without extraction, deduplication or scoring. Most lines do not match, so this is dominated by the cost of
 * rejecting a line.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PatternBenchmark {

    /**
     * Single-line patterns, applied line by line as the extractor does.
     */
    private List<SecretPattern> patterns;

    /**
     * Realistic lines, see {@link BenchSupport#lines()}.
     */
    private List<String> lines;

    @Setup
    public void setUp() {
        // The configured pattern set, as the extractor uses it, so the benchmark follows changes to the default rules
        // (for example the bundled gitleaks rules, #43) instead of a fixed list.
        patterns = BenchSupport.config().getPatterns().getOverrides().stream().filter(p -> !p.isMultiLine()).toList();
        if (patterns.isEmpty()) {
            throw new IllegalStateException("No single-line patterns configured");
        }
        lines = BenchSupport.lines();
    }

    /**
     * Returns the number of matches, so the work cannot be eliminated.
     */
    @Benchmark
    public int allPatternsOnAllLines() {
        var matches = 0;
        for (final var pattern : patterns) {
            for (final var line : lines) {
                final var matcher = pattern.getPattern().matcher(line);
                while (matcher.find()) {
                    matches++;
                }
            }
        }
        return matches;
    }
}
