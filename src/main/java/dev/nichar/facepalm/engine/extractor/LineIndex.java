/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import jakarta.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Maps character offsets of a text to 1-based line numbers.
 * Newline offsets are computed once, so each lookup is a binary search instead of a scan of the preceding text.
 */
final class LineIndex {

    /**
     * Sorted offsets of every {@code '\n'} character in the text.
     */
    private final int[] newlineOffsets;

    LineIndex(@Nonnull final String content) {
        this.newlineOffsets = IntStream.range(0, content.length())
            .filter(i -> content.charAt(i) == '\n')
            .toArray();
    }

    /**
     * Returns the 1-based line number containing the given character offset,
     * i.e. the number of {@code '\n'} characters strictly before the offset, plus one.
     */
    int lineNumberAt(final int offset) {
        final int index = Arrays.binarySearch(newlineOffsets, offset);
        // Found: the offset is itself a newline, which is not counted. Not found: insertion point = newlines before.
        final int newlinesBefore = index >= 0 ? index : -index - 1;
        return newlinesBefore + 1;
    }
}
