/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The console log of the CLI (#233) keeps the {@code [LEVEL] message} format of the Maven console it replaces.
 */
class ConsoleFacepalmLogTest {

    @Test
    void writes_every_level_with_its_prefix_when_debug_is_enabled() {
        final var out = new ByteArrayOutputStream();
        final var log = new ConsoleFacepalmLog(new PrintStream(out, true, StandardCharsets.UTF_8), true);

        log.debug("d");
        log.info("i");
        log.warn("w");
        log.error("e");
        log.warn("cause", new IllegalStateException("boom"));

        assertThat(log.isDebugEnabled()).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8).lines())
            .startsWith("[DEBUG] d", "[INFO] i", "[WARNING] w", "[ERROR] e", "[WARNING] cause",
                "java.lang.IllegalStateException: boom");
    }

    @Test
    void leaves_out_debug_messages_when_debug_is_disabled() {
        final var out = new ByteArrayOutputStream();
        final var log = new ConsoleFacepalmLog(new PrintStream(out, true, StandardCharsets.UTF_8), false);

        log.debug("d");
        log.info("i");

        assertThat(log.isDebugEnabled()).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8).lines()).containsExactly("[INFO] i");
    }
}
