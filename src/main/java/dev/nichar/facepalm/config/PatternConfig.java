/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import dev.nichar.facepalm.pattern.SecretPattern;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Configuration for additional detectors and explicit exclusions.
 */
@Data
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
@NoArgsConstructor
public class PatternConfig {

    /**
     * User-defined patterns appended to the built-in registry.
     */
    private volatile List<SecretPattern> overrides;

    /** Names of detectors explicitly disabled by the user. */
    private volatile Set<String> disabledPatterns = Set.of();

    /** Retains the original programmatic configuration constructor. */
    public PatternConfig(final List<SecretPattern> overrides) {
        this.overrides = overrides == null ? null : new ArrayList<>(overrides);
    }

    /** Immutable resolved registry, excluded from configuration identity and diagnostic output. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient volatile List<SecretPattern> activePatterns;

    /** Copies additional detectors and invalidates the resolved registry. */
    public void setOverrides(final List<SecretPattern> overrides) {
        synchronized (this) {
            this.overrides = overrides == null ? null : new ArrayList<>(overrides);
            activePatterns = null;
        }
    }

    /** Copies exclusions so subsequent changes must pass through this setter. */
    public void setDisabledPatterns(final Set<String> disabledPatterns) {
        synchronized (this) {
            this.disabledPatterns = disabledPatterns == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(disabledPatterns));
            activePatterns = null;
        }
    }

    /** Returns the same immutable registry until configuration changes. Maven resolves it before scanning. */
    public List<SecretPattern> getOverrides() {
        final var resolved = activePatterns;
        return resolved != null ? resolved : resolveActivePatterns();
    }

    /** Serializes first resolution with setters; normal reads only access the volatile snapshot. */
    private synchronized List<SecretPattern> resolveActivePatterns() {
        if (activePatterns == null) {
            activePatterns = buildActivePatterns();
        }
        return activePatterns;
    }

    /** Validates the complete configuration and builds a snapshot without mutating the defaults. */
    private List<SecretPattern> buildActivePatterns() {
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
