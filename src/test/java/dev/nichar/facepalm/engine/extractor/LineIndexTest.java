/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LineIndexTest {

    private static final String TEXT = "first\nsecond\r\n\nfourth";

    /**
     * Offsets map to the line that contains them; a newline belongs to the line it terminates.
     */
    @Test
    void line_number_at_offset() {
        final LineIndex index = new LineIndex(TEXT);
        Assertions.assertEquals(1, index.lineNumberAt(0)); // 'f' of "first"
        Assertions.assertEquals(1, index.lineNumberAt(5)); // '\n' ending line 1
        Assertions.assertEquals(2, index.lineNumberAt(6)); // 's' of "second"
        Assertions.assertEquals(2, index.lineNumberAt(12)); // '\r' of CRLF stays on line 2
        Assertions.assertEquals(2, index.lineNumberAt(13)); // '\n' of CRLF
        Assertions.assertEquals(3, index.lineNumberAt(14)); // empty line 3 ('\n')
        Assertions.assertEquals(4, index.lineNumberAt(15)); // 'f' of "fourth"
        Assertions.assertEquals(4, index.lineNumberAt(20)); // last character
        Assertions.assertEquals(4, index.lineNumberAt(21)); // end of text (offset == length)
    }

    @Test
    void text_without_newlines_is_single_line() {
        final LineIndex index = new LineIndex("no newline here");
        Assertions.assertEquals(1, index.lineNumberAt(0));
        Assertions.assertEquals(1, index.lineNumberAt(15));
    }

    @Test
    void empty_text_is_single_line() {
        Assertions.assertEquals(1, new LineIndex("").lineNumberAt(0));
    }

    /**
     * Guards against regressions by comparing with the previous substring-based computation on random text.
     */
    @Test
    void matches_previous_substring_computation() {
        final Random random = new Random(14);
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            final int roll = random.nextInt(10);
            text.append(roll == 0 ? '\n' : roll == 1 ? '\r' : (char) ('a' + roll));
        }
        final String content = text.toString();
        final LineIndex index = new LineIndex(content);

        for (int offset = 0; offset <= content.length(); offset++) {
            final int expected = (int) content.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1;
            Assertions.assertEquals(expected, index.lineNumberAt(offset), "offset " + offset);
        }
    }
}
