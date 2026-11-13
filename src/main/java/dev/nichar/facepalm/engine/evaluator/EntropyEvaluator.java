/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Evaluates a secret's randomness using Shannon entropy.
 * High entropy often signals genuine credentials, while low entropy indicates repetitive or dummy data.
 */
@Named
@Singleton
class EntropyEvaluator implements FindingEvaluator {

    /**
     * Entropy (bits per character) above which a long value counts as random.
     */
    private static final double HIGH_ENTROPY = 4.5;

    /**
     * Share of the maximum possible entropy ({@code log2(length)}) above which a shorter value counts as random. A
     * value of {@code n} characters can never exceed {@code log2(n)} bits, so a fixed 4.5 would never reward a
     * 20-character key such as an AWS access key ID (at most 4.32).
     */
    private static final double HIGH_ENTROPY_RATIO = 0.9;

    /**
     * Minimum length for the high entropy bonus, so short words such as {@code password1} do not earn it.
     */
    private static final int HIGH_ENTROPY_MIN_LENGTH = 12;

    /**
     * Entropy below which a value counts as repetitive or predictable.
     */
    private static final double LOW_ENTROPY = 3.0;

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        // Skip entropy checks for high-signal formats like Private Keys.
        if (finding.getPatternName().contains("Private Key") || finding.getSecretValue().contains("-----")) {
            return;
        }

        final var value = finding.getSecretValue();
        final var entropy = getShannonEntropy(value);
        if (value.length() >= HIGH_ENTROPY_MIN_LENGTH && entropy > highEntropyThreshold(value.length())) {
            // Random strings typical of API keys or passwords.
            finding.log(String.format(Locale.ROOT, "High Entropy (%.2f)", entropy), 10, 20);
        } else if (entropy < LOW_ENTROPY) {
            // Repetitive or predictable strings likely to be noise.
            finding.log(String.format(Locale.ROOT, "Low Entropy (%.2f)", entropy), 0, -40);
        }
    }

    /**
     * Entropy a value of the given length needs for the high entropy bonus: {@value #HIGH_ENTROPY} bits, or 90% of
     * the maximum {@code log2(length)} for values too short to ever reach it.
     */
    static double highEntropyThreshold(final int length) {
        return Math.min(HIGH_ENTROPY, HIGH_ENTROPY_RATIO * Math.log(length) / Math.log(2));
    }

    /**
     * Calculates Shannon entropy for the given string.
     * Values above 4.5 typically indicate high randomness.
     */
    private double getShannonEntropy(@Nullable final String string) {
        if (string == null || string.isEmpty()) {
            return 0;
        }
        final Map<Character, Integer> freq = new HashMap<>();
        for (final var ch : string.toCharArray()) {
            freq.merge(ch, 1, Integer::sum);
        }
        var entropy = 0.0;
        for (final int count : freq.values()) {
            final double p = (double) count / string.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }
}
