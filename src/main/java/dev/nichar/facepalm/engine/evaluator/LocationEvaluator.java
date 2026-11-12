/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import jakarta.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Evaluates findings based on their filesystem location.
 * Elevates risk for production paths and de-prioritizes test or mock directories.
 *
 * <p>
 * Markers are matched against the path relative to the scan root, independent of the OS separator:
 * <ul>
 * <li>A marker with only letters and digits (e.g. {@code test}) matches a whole word of a path segment, split at
 * non-alphanumeric characters and camelCase boundaries, optionally in plural: {@code src/test/},
 * {@code __tests__}, {@code FooTest.java} and {@code app.spec.ts} match, {@code latest} and {@code contest} do
 * not.</li>
 * <li>Any other marker (e.g. {@code src/main/}, {@code .env}) matches at a segment start: {@code .env} matches
 * {@code .env} and {@code .env.local}, {@code src/main/} matches {@code src/main/java}.</li>
 * </ul>
 */
@Named
@Singleton
class LocationEvaluator implements FindingEvaluator {

    /**
     * Splits a path segment into words: at runs of non-alphanumeric characters ({@code app.spec.ts},
     * {@code __tests__}) and at camelCase boundaries ({@code FooTest} becomes {@code Foo}, {@code Test}).
     */
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");

    /**
     * Identifies word markers (letters and digits only, e.g. {@code test}), which match whole words of a segment.
     * Markers with other characters (e.g. {@code src/main/}, {@code .env}) match at a segment start instead.
     */
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]+");

    @Inject
    private FacepalmConfig config;

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        final var segments = segments(context.getRelativePath());
        final var conf = config.getEvaluators();
        // Increase risk for findings in production-critical paths.
        if (conf.getProdPathMarkers().stream().anyMatch(marker -> matches(segments, marker))) {
            finding.log("Production Path Marker", 20, 0);
        }

        // Reduce risk for findings in test or mock environments.
        if (conf.getTestPathMarkers().stream().anyMatch(marker -> matches(segments, marker))) {
            finding.log("Test/Mock Path Marker", -30, -20);
        }
    }

    @Nonnull
    private static List<String> segments(@Nonnull final Path relativePath) {
        return StreamSupport.stream(relativePath.spliterator(), false)
            .map(Path::toString)
            .collect(Collectors.toList());
    }

    static boolean matches(@Nonnull final List<String> segments, @Nonnull final String marker) {
        final var lowerMarker = marker.toLowerCase(Locale.ROOT);
        if (ALPHANUMERIC.matcher(lowerMarker).matches()) {
            return segments.stream()
                .flatMap(segment -> Arrays.stream(WORD_BOUNDARY.split(segment)))
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(word -> word.equals(lowerMarker) || word.equals(lowerMarker + "s"));
        }
        final var lowerSegments = new ArrayList<String>();
        segments.forEach(segment -> lowerSegments.add(segment.toLowerCase(Locale.ROOT)));
        final var path = "/" + String.join("/", lowerSegments);
        final var needle = lowerMarker.startsWith("/") ? lowerMarker : "/" + lowerMarker;
        return path.contains(needle);
    }
}
