/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import jakarta.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import lombok.Getter;

/**
 * Immutable snapshot of a file being analyzed.
 * Provides access to raw content, line-delimited text, and filesystem metadata.
 */
@Getter
public class FileContext {

    /**
     * Filesystem path to the source file.
     */
    private final Path path;

    /**
     * Path relative to the scan root, used for location heuristics so that directories above the project never
     * influence scoring.
     */
    private final Path relativePath;

    /**
     * Unmodified content for multi-line regex matching.
     */
    private final String fullContent;

    /**
     * Line-delimited content for single-line analysis and context extraction.
     */
    private final List<String> lines;

    public FileContext(@Nonnull final Path path, @Nonnull final String fullContent, @Nonnull final List<String> lines) {
        this(path, path, fullContent, lines);
    }

    public FileContext(@Nonnull final Path path,
                       @Nonnull final Path relativePath,
                       @Nonnull final String fullContent,
                       @Nonnull final List<String> lines) {
        this.path = path;
        this.relativePath = relativePath;
        this.fullContent = fullContent;
        this.lines = lines;
    }

    /**
     * Returns the line at the specified index, or an empty string if out of bounds.
     */
    @Nonnull
    public String getLineOrEmpty(final int index) {
        return (index >= 0 && index < lines.size()) ? lines.get(index) : "";
    }
}
