/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class EngineConfigTest {

    @Test
    void skip_dirs_default_when_not_configured() {
        final var config = new EngineConfig();
        assertEquals(EngineConfig.SKIP_DIRS, config.getSkipDirs());
    }

    @Test
    void skip_dirs_default_when_maven_injects_empty_set() {
        final var config = new EngineConfig();
        config.setSkipDirs(Set.of());
        assertEquals(EngineConfig.SKIP_DIRS, config.getSkipDirs());
    }

    @Test
    void skip_dirs_user_value_replaces_defaults() {
        final var config = new EngineConfig();
        config.setSkipDirs(Set.of("logs"));
        assertEquals(Set.of("logs"), config.getSkipDirs());
    }
}
