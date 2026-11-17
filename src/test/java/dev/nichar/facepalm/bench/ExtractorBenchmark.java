/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.bench;

import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.extractor.RegexSecretExtractor;
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
 * {@link RegexSecretExtractor} on a 200-line configuration file: matching, snippet masking and deduplication.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ExtractorBenchmark {

    /**
     * Lines of the extracted file.
     */
    private static final int LINES = 200;

    /**
     * Extractor wired by the real container.
     */
    private RegexSecretExtractor extractor;

    /**
     * File to extract from.
     */
    private FileContext file;

    @Setup
    public void setUp() {
        extractor = BenchSupport.container(BenchSupport.config()).getInstance(RegexSecretExtractor.class);
        file = BenchSupport.fileContext(BenchSupport.fileLines(LINES, 1));
    }

    @Benchmark
    public List<Finding> extract() {
        return extractor.extract(file);
    }
}
