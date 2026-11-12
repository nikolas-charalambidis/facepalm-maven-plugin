/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import jakarta.annotation.Nonnull;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Analyzes surrounding code for environment-specific keywords.
 * Identifies markers like "mock" or "prod" to refine threat confidence.
 */
@Named
@Singleton
class SurroundingContextEvaluator implements FindingEvaluator {

    @Inject
    private FacepalmConfig config;

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        final int idx = finding.getLineNumber() - 1;
        // Analyze a window of neighboring lines for environmental clues.
        final var chunk = (context.getLineOrEmpty(idx - 1) + " " +
            context.getLineOrEmpty(idx) + " " +
            context.getLineOrEmpty(idx + 1)).toLowerCase(Locale.ROOT);

        final var conf = config.getEvaluators();
        // Reduce confidence if mock or test keywords are found in the immediate vicinity.
        if (conf.getMockContextMarkers().stream().anyMatch(chunk::contains)) {
            finding.log("Mock Context Keywords Found", 0, -40);
        }
        // Increase risk if production-specific markers are present.
        if (conf.getProdContextMarkers().stream().anyMatch(chunk::contains)) {
            finding.log("Production Context Keywords Found", 20, 0);
        }
    }
}
