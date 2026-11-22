/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Test log that records "LEVEL message" lines. Debug messages are not written, like with a Maven build without
 * {@code -X}.
 */
@RequiredArgsConstructor
public final class RecordingLog implements FacepalmLog {

    /**
     * The recorded lines.
     */
    private final List<String> lines;

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(final CharSequence message) {
        // Not written.
    }

    @Override
    public void info(final CharSequence message) {
        lines.add("INFO " + message);
    }

    @Override
    public void warn(final CharSequence message) {
        lines.add("WARN " + message);
    }

    @Override
    public void warn(final CharSequence message, final Throwable error) {
        lines.add("WARN " + message);
    }

    @Override
    public void error(final CharSequence message) {
        lines.add("ERROR " + message);
    }
}
