/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import dev.nichar.facepalm.pattern.SecretPattern;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for defining or overriding secret detection patterns.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatternConfig {

    /**
     * User-defined patterns to supplement or replace built-in logic.
     */
    private List<SecretPattern> overrides;

    /**
     * Returns the active detection patterns, falling back to defaults if none are defined. An empty list also means
     * the defaults: scanning with zero patterns would pass every build as if the project were clean (#277).
     */
    public List<SecretPattern> getOverrides() {
        return overrides != null && !overrides.isEmpty() ? overrides : SecretPatternRegistry.DEFAULT_PATTERNS;
    }

    /**
     * Returns whether {@code overrides} was configured at all, even as an empty list. Custom patterns are not
     * supported yet (#79), so the scan goal rejects such a configuration instead of ignoring it.
     */
    public boolean isConfigured() {
        return overrides != null;
    }
}
