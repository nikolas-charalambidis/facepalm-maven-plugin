/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Effective skip directories for every combination of {@code useDefaultSkipDirs} and {@code skipDirs} (#258).
 * Maven injects an empty set when {@code skipDirs} is not configured, so "not set" and "empty" behave alike.
 */
class EngineConfigTest {

    @Test
    void skip_dirs_default_when_not_configured() {
        final var config = new EngineConfig();
        assertEquals(EngineConfig.SKIP_DIRS, config.getSkipDirs());
        assertThat(config.isSkipDirsReplacingDefaults()).isFalse();
    }

    @Test
    void skip_dirs_default_when_maven_injects_empty_set() {
        final var config = config(null, Set.of());
        assertEquals(EngineConfig.SKIP_DIRS, config.getSkipDirs());
        assertThat(config.isSkipDirsReplacingDefaults()).isFalse();
    }

    @Test
    void skip_dirs_user_value_replaces_defaults_when_use_default_is_not_set() {
        final var config = config(null, Set.of("logs"));
        assertEquals(Set.of("logs"), config.getSkipDirs());
        assertThat(config.isSkipDirsReplacingDefaults()).isTrue();
    }

    @Test
    void use_default_true_without_skip_dirs_gives_defaults() {
        assertEquals(EngineConfig.SKIP_DIRS, config(true, null).getSkipDirs());
        assertEquals(EngineConfig.SKIP_DIRS, config(true, Set.of()).getSkipDirs());
    }

    @Test
    void use_default_true_adds_skip_dirs_to_defaults() {
        final var config = config(true, Set.of("logs", "generated"));

        final var expected = new HashSet<>(EngineConfig.SKIP_DIRS);
        expected.add("logs");
        expected.add("generated");
        assertEquals(expected, config.getSkipDirs());
        assertThat(config.isSkipDirsReplacingDefaults()).isFalse();
    }

    @Test
    void use_default_false_without_skip_dirs_skips_nothing() {
        assertThat(config(false, null).getSkipDirs()).isEmpty();
        assertThat(config(false, Set.of()).getSkipDirs()).isEmpty();
    }

    @Test
    void use_default_false_keeps_only_skip_dirs() {
        final var config = config(false, Set.of("logs"));
        assertEquals(Set.of("logs"), config.getSkipDirs());
        assertThat(config.isSkipDirsReplacingDefaults()).isFalse();
    }

    private static EngineConfig config(final Boolean useDefaultSkipDirs, final Set<String> skipDirs) {
        final var config = new EngineConfig();
        config.setUseDefaultSkipDirs(useDefaultSkipDirs);
        config.setSkipDirs(skipDirs);
        return config;
    }
}
