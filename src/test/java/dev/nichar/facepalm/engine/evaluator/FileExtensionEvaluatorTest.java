/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.EvaluatorConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileExtensionEvaluatorTest {

    /**
     * Issue #288: {@code .env} variants such as {@code .env.local} are as sensitive as {@code .env}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        ".env", ".env.local", ".env.production", ".ENV.Development", "app.env", "app.properties",
        "application.yml" })
    void high_risk_files_get_the_bonus(final String fileName) throws Exception {
        assertEquals(List.of("High Risk Configuration File (+15/+20)"), evaluate(fileName));
    }

    /**
     * Templates stay low-risk, as {@code lowRiskExtensions} ({@code .example}, {@code .sample}) intends.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        ".env.example", ".env.sample", "README.md", "app.log" })
    void low_risk_files_get_the_discount(final String fileName) throws Exception {
        assertEquals(List.of("Documentation/Log File (-30/-40)"), evaluate(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "App.java", ".environment", "app.envelope", "x.properties.bak" })
    void other_files_are_neutral(final String fileName) throws Exception {
        assertEquals(List.of(), evaluate(fileName));
    }

    private static List<String> evaluate(final String fileName) throws ReflectiveOperationException {
        final var finding = Finding.builder().patternName("Test").deduplicationHash("h").secretValue("v")
            .riskScore(50).confidenceScore(50).build();
        final var root = Path.of("/work/project");
        final var file = root.resolve(fileName);
        evaluator().evaluate(finding, new FileContext(file, root.relativize(file), "", List.of()));
        return finding.getScoreHistory();
    }

    private static FileExtensionEvaluator evaluator() throws ReflectiveOperationException {
        final var evaluator = new FileExtensionEvaluator();
        final var config = new FacepalmConfig();
        config.setEvaluators(new EvaluatorConfig());
        final Field field = FileExtensionEvaluator.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(evaluator, config);
        return evaluator;
    }
}
