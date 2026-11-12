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
 * Line break offsets are computed once, so each lookup is a binary search instead of a scan of the preceding text.
 *
 * <p>
 * Line breaks are exactly those of the {@code \R} regex used to split files into lines: CR LF as one break, and
 * each of LF, CR, VT (U+000B), FF (U+000C), NEL (U+0085), LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029).
 * Multi-line findings therefore get the same line numbers as single-line ones.
 */
final class LineIndex {

    /**
     * Sorted offsets of the last character of every line break in the text (the {@code \n} of {@code \r\n}).
     */
    private final int[] newlineOffsets;

    LineIndex(@Nonnull final String content) {
        this.newlineOffsets = IntStream.range(0, content.length())
            .filter(i -> endsLineBreak(content, i))
            .toArray();
    }

    /**
     * Returns whether the character at {@code i} is the last character of a line break.
     */
    private static boolean endsLineBreak(@Nonnull final String content, final int i) {
        return switch (content.charAt(i)) {
            // A \r followed by \n is part of one \r\n break, which ends at the \n.
            case '\r' -> i + 1 >= content.length() || content.charAt(i + 1) != '\n';
            case '\n', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029' -> true;
            default -> false;
        };
    }

    /**
     * Returns the 1-based line number containing the given character offset,
     * i.e. the number of line breaks ending strictly before the offset, plus one.
     */
    int lineNumberAt(final int offset) {
        final int index = Arrays.binarySearch(newlineOffsets, offset);
        // Found: the offset ends a line break, which belongs to its line. Not found: insertion point = breaks before.
        final int newlinesBefore = index >= 0 ? index : -index - 1;
        return newlinesBefore + 1;
    }
}
