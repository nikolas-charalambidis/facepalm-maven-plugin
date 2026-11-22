/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.module;

import dev.nichar.facepalm.engine.FacepalmLog;
import lombok.RequiredArgsConstructor;
import org.apache.maven.plugin.logging.Log;

/**
 * Adapts the Maven {@link Log} of a Mojo to the {@link FacepalmLog} of the engine.
 */
@RequiredArgsConstructor
public final class MavenFacepalmLog implements FacepalmLog {

    /**
     * The Mojo's log.
     */
    private final Log log;

    @Override
    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    @Override
    public void debug(final CharSequence message) {
        log.debug(message);
    }

    @Override
    public void info(final CharSequence message) {
        log.info(message);
    }

    @Override
    public void warn(final CharSequence message) {
        log.warn(message);
    }

    @Override
    public void warn(final CharSequence message, final Throwable error) {
        log.warn(message, error);
    }

    @Override
    public void error(final CharSequence message) {
        log.error(message);
    }
}
