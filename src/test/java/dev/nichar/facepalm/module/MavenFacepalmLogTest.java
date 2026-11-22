/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

/**
 * The adapter passes every engine log call to the Mojo's Maven log unchanged (#233), so the build output stays the
 * same.
 */
class MavenFacepalmLogTest {

    @Test
    void passes_every_call_to_the_maven_log() {
        final List<String> calls = new ArrayList<>();
        final var cause = new IllegalStateException("boom");
        final var log = new MavenFacepalmLog(new SystemStreamLog() {

            @Override
            public boolean isDebugEnabled() {
                return true;
            }

            @Override
            public void debug(final CharSequence content) {
                calls.add("debug " + content);
            }

            @Override
            public void info(final CharSequence content) {
                calls.add("info " + content);
            }

            @Override
            public void warn(final CharSequence content) {
                calls.add("warn " + content);
            }

            @Override
            public void warn(final CharSequence content, final Throwable error) {
                calls.add("warn " + content + " " + (error == cause));
            }

            @Override
            public void error(final CharSequence content) {
                calls.add("error " + content);
            }
        });

        log.debug("d");
        log.info("i");
        log.warn("w");
        log.warn("c", cause);
        log.error("e");

        assertThat(log.isDebugEnabled()).isTrue();
        assertThat(calls).containsExactly("debug d", "info i", "warn w", "warn c true", "error e");
    }
}
