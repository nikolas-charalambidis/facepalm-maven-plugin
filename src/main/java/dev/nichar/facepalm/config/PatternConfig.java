/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import dev.nichar.facepalm.pattern.SecretPattern;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for additional detectors and explicit exclusions.
 */
@Data
@NoArgsConstructor
public class PatternConfig {

    /**
     * User-defined patterns appended to the built-in registry.
     */
    private List<SecretPattern> overrides;

    /** Names of detectors explicitly disabled by the user. */
    private Set<String> disabledPatterns = Set.of();

    /** Retains the original programmatic configuration constructor. */
    public PatternConfig(final List<SecretPattern> overrides) {
        this.overrides = overrides == null ? null : new ArrayList<>(overrides);
    }

    /** Returns the immutable effective registry, with defaults first and additions last. */
    public List<SecretPattern> getOverrides() {
        final var all = new ArrayList<>(SecretPatternRegistry.DEFAULT_PATTERNS);
        if (overrides != null) {
            all.addAll(overrides);
        }
        final Set<String> names = new HashSet<>();
        for (final var pattern : all) {
            if (pattern == null || pattern.getName() == null || pattern.getName().isBlank()
                || pattern.getPattern() == null || pattern.getBaseRisk() < 0 || pattern.getBaseRisk() > 100
                || pattern.getBaseConfidence() < 0 || pattern.getBaseConfidence() > 100) {
                throw new IllegalArgumentException("Invalid custom pattern definition");
            }
            if (!names.add(pattern.getName().strip())) {
                throw new IllegalArgumentException("Duplicate pattern name: " + pattern.getName());
            }
        }
        final Set<String> disabled = new HashSet<>();
        if (disabledPatterns != null) {
            for (final var name : disabledPatterns) {
                if (name == null || !names.contains(name.strip())) {
                    throw new IllegalArgumentException("Unknown disabled pattern: " + name);
                }
                disabled.add(name.strip());
            }
        }
        final var active = all.stream().filter(pattern -> !disabled.contains(pattern.getName().strip())).toList();
        if (active.isEmpty()) {
            throw new IllegalArgumentException("At least one detection pattern must remain active");
        }
        return active;
    }

    /** Returns whether additional patterns were explicitly configured. */
    public boolean isConfigured() {
        return overrides != null;
    }
}
