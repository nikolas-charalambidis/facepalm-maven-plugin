/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.FileContext;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Fuzzes {@link RegexSecretExtractor#extract} with every default pattern over random file content (#178). It must
 * never throw, and every finding must have a non-empty value taken from the content and a line number inside the
 * file. Catastrophic backtracking shows up as a timeout: ClusterFuzzLite stops an input after the {@code timeout} in
 * {@code .clusterfuzzlite/build.sh}.
 * <p>
 * {@code mvn test} runs it once, in regression mode. {@code JAZZER_FUZZ=1 mvn test -Dtest=RegexSecretExtractorFuzzTest}
 * fuzzes it locally, and ClusterFuzzLite calls {@link #fuzzerTestOneInput} in CI.
 */
class RegexSecretExtractorFuzzTest {

    /**
     * Extractor with the default configuration, created once: building the container per input would dominate the run.
     */
    private static final RegexSecretExtractor EXTRACTOR = TestContainer.create(new FacepalmConfig())
        .getInstance(RegexSecretExtractor.class);

    /**
     * Path of the fuzzed file; it does not influence extraction.
     */
    private static final Path PATH = Path.of("fuzz.properties");

    @FuzzTest(maxDuration = "1m")
    void extract(final FuzzedDataProvider data) {
        fuzzerTestOneInput(data);
    }

    /**
     * Entry point for ClusterFuzzLite.
     */
    public static void fuzzerTestOneInput(final FuzzedDataProvider data) {
        final var content = data.consumeRemainingAsString();
        // Split as ScannerEngine does.
        final var lines = Arrays.asList(content.split("\\R"));
        for (final var finding : EXTRACTOR.extract(new FileContext(PATH, content, lines))) {
            final var value = finding.getSecretValue();
            if (value.isEmpty() || !content.contains(value)) {
                throw new IllegalStateException("Value not taken from the content: " + finding.getPatternName());
            }
            if (finding.getLineNumber() < 1 || finding.getLineNumber() > lines.size()) {
                throw new IllegalStateException("Line " + finding.getLineNumber() + " outside 1.." + lines.size()
                    + ": " + finding.getPatternName());
            }
        }
    }
}
