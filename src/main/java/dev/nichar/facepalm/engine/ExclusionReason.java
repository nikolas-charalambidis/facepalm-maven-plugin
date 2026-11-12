/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Categorizes reasons for file exclusion from the scanning process.
 */
@Getter
@RequiredArgsConstructor
public enum ExclusionReason {

    /**
     * Non-text asset detected.
     */
    BINARY_FILE("Binary file detected"),

    /**
     * File path matches a configured exclusion filter.
     */
    REGEX_MATCH("Path matched exclusion regex"),

    /**
     * File size exceeds the safety threshold for in-memory analysis.
     */
    SIZE_EXCEEDED("File size exceeds limit"),

    /**
     * File could not be accessed due to filesystem constraints.
     */
    IO_ERROR("Unreadable/Access denied");

    /**
     * Localized description for reporting and diagnostic logging.
     */
    private final String description;
}
