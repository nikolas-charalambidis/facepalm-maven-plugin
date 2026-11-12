/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.EvaluatorConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationEvaluatorTest {

    @Test
    void word_markers_match_whole_words_of_segments() {
        assertTrue(LocationEvaluator.matches(segments("src", "test", "java", "App.java"), "test"));
        assertTrue(LocationEvaluator.matches(segments("web", "__tests__", "app.js"), "test"));
        assertTrue(LocationEvaluator.matches(segments("src", "main", "java", "FooTest.java"), "test"));
        assertTrue(LocationEvaluator.matches(segments("web", "app.spec.ts"), "spec"));
        assertTrue(LocationEvaluator.matches(segments("src", "MockServer.java"), "mock"));
        assertTrue(LocationEvaluator.matches(segments("conf", "application-config.yml"), "config"));
    }

    @Test
    void word_markers_do_not_match_inside_words() {
        assertFalse(LocationEvaluator.matches(segments("latest", "app.properties"), "test"));
        assertFalse(LocationEvaluator.matches(segments("contest", "app.properties"), "test"));
        assertFalse(LocationEvaluator.matches(segments("attestation.yml"), "test"));
        assertFalse(LocationEvaluator.matches(segments("specification.md"), "spec"));
    }

    @Test
    void punctuated_markers_match_at_segment_start() {
        assertTrue(LocationEvaluator.matches(segments("src", "main", "resources", "app.yml"), "src/main/"));
        assertTrue(LocationEvaluator.matches(segments(".env"), ".env"));
        assertTrue(LocationEvaluator.matches(segments("deploy", ".env.local"), ".env"));
        assertFalse(LocationEvaluator.matches(segments("lib", "src", "mainframe", "x.yml"), "src/main/"));
    }

    /**
     * Directories above the scan root (e.g. a CI workspace named "latest-svc" or "test-env") must not lower scores.
     */
    @Test
    void evaluate_ignores_directories_above_the_scan_root() throws Exception {
        final var root = Path.of("/home/runner/work/test-env/project");
        final var file = root.resolve("src/main/resources/app.properties");
        final var finding = finding();

        evaluator().evaluate(finding, new FileContext(file, root.relativize(file), "", List.of()));

        assertEquals(List.of("Production Path Marker (+20/+0)"), finding.getScoreHistory());
    }

    @Test
    void evaluate_lowers_scores_in_test_directories() throws Exception {
        final var root = Path.of("/work/project");
        final var file = root.resolve("src/test/resources/app.properties");
        final var finding = finding();

        evaluator().evaluate(finding, new FileContext(file, root.relativize(file), "", List.of()));

        assertEquals(List.of("Test/Mock Path Marker (-30/-20)"), finding.getScoreHistory());
    }

    private static List<String> segments(final String... segments) {
        return List.of(segments);
    }

    private static Finding finding() {
        return Finding.builder().patternName("Test").deduplicationHash("h").secretValue("v").riskScore(50)
            .confidenceScore(50).build();
    }

    private static LocationEvaluator evaluator() throws ReflectiveOperationException {
        final var evaluator = new LocationEvaluator();
        final var config = new FacepalmConfig();
        config.setEvaluators(new EvaluatorConfig());
        final Field field = LocationEvaluator.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(evaluator, config);
        return evaluator;
    }
}
