/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import java.io.PrintStream;
import lombok.RequiredArgsConstructor;

/**
 * Writes log messages to a stream with a {@code [LEVEL]} prefix, in the format of the Maven console output.
 */
@RequiredArgsConstructor
public final class ConsoleFacepalmLog implements FacepalmLog {

    /**
     * Target of all messages.
     */
    private final PrintStream out;

    /**
     * Whether debug messages are written.
     */
    private final boolean debug;

    @Override
    public boolean isDebugEnabled() {
        return debug;
    }

    @Override
    public void debug(final CharSequence message) {
        if (debug) {
            write("DEBUG", message);
        }
    }

    @Override
    public void info(final CharSequence message) {
        write("INFO", message);
    }

    @Override
    public void warn(final CharSequence message) {
        write("WARNING", message);
    }

    @Override
    public void warn(final CharSequence message, final Throwable error) {
        write("WARNING", message);
        error.printStackTrace(out);
    }

    @Override
    public void error(final CharSequence message) {
        write("ERROR", message);
    }

    private void write(final String level, final CharSequence message) {
        out.println("[" + level + "] " + message);
    }
}
