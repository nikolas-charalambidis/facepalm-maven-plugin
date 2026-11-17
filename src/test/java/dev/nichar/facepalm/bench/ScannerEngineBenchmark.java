/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.bench;

import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.ScannerEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * {@link ScannerEngine#scan} end to end on a generated project of 200 files with 50 lines each: discovery,
 * {@code .gitignore} loading, reading, extraction, evaluation and post-processing, on one thread. The project is
 * written to a temporary directory once per trial, so after the first iteration it is read from the OS page cache.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ScannerEngineBenchmark {

    /**
     * Files of the generated project.
     */
    private static final int FILES = 200;

    /**
     * Lines per file of the generated project.
     */
    private static final int LINES_PER_FILE = 50;

    /**
     * Root of the generated project.
     */
    private Path root;

    /**
     * Engine wired by the real container.
     */
    private ScannerEngine engine;

    @Setup
    public void setUp() throws IOException {
        root = Files.createTempDirectory("facepalm-bench");
        BenchSupport.writeProject(root, FILES, LINES_PER_FILE);
        engine = BenchSupport.container(BenchSupport.config()).getInstance(ScannerEngine.class);
    }

    @TearDown
    public void tearDown() throws IOException {
        BenchSupport.delete(root);
    }

    @Benchmark
    public List<Finding> scan() throws IOException, InterruptedException {
        return engine.scan(root);
    }
}
