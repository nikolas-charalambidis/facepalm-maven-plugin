/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Splits a comma-separated value, such as {@code -DskipDirs=a,b} in Maven or {@code --skip-dirs a,b} in the CLI, the
 * same way in both (#235).
 */
@UtilityClass
public class CommaSeparatedValues {

    /**
     * Returns the unique, stripped, non-blank elements in their original order; an empty set for {@code null} or a
     * blank value.
     */
    @Nonnull
    public static Set<String> split(@Nullable final String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split(","))
            // Removes all Unicode whitespace (cleaner than trim()).
            .map(String::strip)
            .filter(Predicate.not(String::isBlank))
            // Keeps the insertion order while ensuring uniqueness.
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
