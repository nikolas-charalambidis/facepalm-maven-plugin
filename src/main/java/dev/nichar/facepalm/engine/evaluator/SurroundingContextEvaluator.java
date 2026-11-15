/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
        // The secret itself is not context: "live" in an sk_live_ key says nothing about its surroundings (#225).
        var chunk = (context.getLineOrEmpty(idx - 1) + " " +
            context.getLineOrEmpty(idx) + " " +
            context.getLineOrEmpty(idx + 1)).toLowerCase(Locale.ROOT);
        if (!finding.getSecretValue().isEmpty()) {
            chunk = chunk.replace(finding.getSecretValue().toLowerCase(Locale.ROOT), " ");
        }

        final var conf = config.getEvaluators();
        // Reduce confidence if mock or test keywords are found in the immediate vicinity.
        if (containsWord(chunk, conf.getMockContextMarkers())) {
            finding.log("Mock Context Keywords Found", 0, -40);
        }
        // Increase risk if production-specific markers are present.
        if (containsWord(chunk, conf.getProdContextMarkers())) {
            finding.log("Production Context Keywords Found", 20, 0);
        }
    }

    /**
     * Whether any marker occurs as a whole word (#225): not inside a longer word ({@code deliver}) or a dotted name
     * such as a host ({@code smtp.example.com}). {@code _} and {@code -} separate words, so {@code PROD_DB_PASSWORD}
     * contains {@code prod}.
     */
    static boolean containsWord(@Nonnull final String text, @Nonnull final List<String> markers) {
        if (markers.isEmpty()) {
            return false;
        }
        final var alternatives = markers.stream()
            .map(marker -> Pattern.quote(marker.toLowerCase(Locale.ROOT)))
            .collect(Collectors.joining("|"));
        return Pattern.compile("(?<![a-z0-9.])(?:" + alternatives + ")(?![a-z0-9.])").matcher(text).find();
    }
}
