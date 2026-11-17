/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.bench;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.evaluator.FindingEvaluator;
import dev.nichar.facepalm.engine.extractor.RegexSecretExtractor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Each {@link FindingEvaluator} on the findings of a realistic file. Evaluators change the score of a finding and
 * append to its history, so every operation evaluates fresh copies; the copies cost the same for every evaluator.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class EvaluatorBenchmark {

    /**
     * Simple class name of the evaluator to measure.
     */
    @Param({
        "EntropyEvaluator", "FileExtensionEvaluator", "LocationEvaluator", "PlaceholderSuppressorEvaluator",
        "PublicExposureEvaluator", "SurroundingContextEvaluator" })
    private String evaluator;

    /**
     * Evaluator selected by {@link #evaluator}.
     */
    private FindingEvaluator selected;

    /**
     * Findings as the extractor reports them, before any evaluation; copied for every operation.
     */
    private List<Finding> findings;

    @Setup
    public void setUp() {
        final var injector = BenchSupport.container(BenchSupport.config());
        selected = injector.getInstance(Key.get(new TypeLiteral<List<FindingEvaluator>>() {
        })).stream()
            .filter(candidate -> candidate.getClass().getSimpleName().equals(evaluator))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No evaluator named " + evaluator));
        findings = injector.getInstance(RegexSecretExtractor.class)
            .extract(BenchSupport.fileContext(BenchSupport.fileLines(200, 2)));
        if (findings.isEmpty()) {
            throw new IllegalStateException("The corpus produced no findings");
        }
    }

    @Benchmark
    public void evaluate(final Blackhole blackhole) {
        for (final var template : findings) {
            final var finding = Finding.builder()
                .patternName(template.getPatternName())
                .deduplicationHash(template.getDeduplicationHash())
                .secretValue(template.getSecretValue())
                .lineNumber(template.getLineNumber())
                .context(template.getContext())
                .contextSnippet(template.getContextSnippet())
                .riskScore(template.getRiskScore())
                .confidenceScore(template.getConfidenceScore())
                .build();
            selected.evaluate(finding, finding.getContext());
            blackhole.consume(finding.getRiskScore());
            blackhole.consume(finding.getConfidenceScore());
        }
    }
}
