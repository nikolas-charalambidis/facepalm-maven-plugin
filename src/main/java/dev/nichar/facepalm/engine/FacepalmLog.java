/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

/**
 * Logging used by the scan engine and the reporter, so that they run without the Maven API (#233). The Mojos adapt the
 * Maven log to it, the CLI writes to the console.
 */
public interface FacepalmLog {

    /**
     * @return {@code true} if debug messages are written
     */
    boolean isDebugEnabled();

    /**
     * Writes a debug message, if debug messages are enabled.
     *
     * @param message the message
     */
    void debug(CharSequence message);

    /**
     * Writes an info message.
     *
     * @param message the message
     */
    void info(CharSequence message);

    /**
     * Writes a warning.
     *
     * @param message the message
     */
    void warn(CharSequence message);

    /**
     * Writes a warning with the error that caused it.
     *
     * @param message the message
     * @param error the cause
     */
    void warn(CharSequence message, Throwable error);

    /**
     * Writes an error message.
     *
     * @param message the message
     */
    void error(CharSequence message);

    /**
     * A log writing to standard output.
     *
     * @param debug whether debug messages are written
     * @return the log
     */
    static FacepalmLog console(final boolean debug) {
        return new ConsoleFacepalmLog(System.out, debug);
    }
}
