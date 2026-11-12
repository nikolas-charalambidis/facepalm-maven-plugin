/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * Lone carriage returns and Unicode line separators are line breaks, like in the {@code \R} split used for
     * single-line findings (issue #73).
     */
    @Test
    void counts_all_line_terminators() {
        final LineIndex index = new LineIndex("a\rb\u2028c\u0085d\r\ne");
        Assertions.assertEquals(1, index.lineNumberAt(0)); // a
        Assertions.assertEquals(2, index.lineNumberAt(2)); // b, after a lone \r
        Assertions.assertEquals(3, index.lineNumberAt(4)); // c, after U+2028
        Assertions.assertEquals(4, index.lineNumberAt(6)); // d, after U+0085
        Assertions.assertEquals(4, index.lineNumberAt(7)); // \r of \r\n stays on line 4
        Assertions.assertEquals(5, index.lineNumberAt(9)); // e, after \r\n
    }

    /**
     * Compares with the {@code \R} regex on random text containing every kind of line break: the line of an offset
     * is the number of {@code \R} matches ending at or before it, plus one.
     */
    @Test
    void matches_regex_line_breaks() {
        final char[] alphabet = {
            'a', 'b', '\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029' };
        final Random random = new Random(73);
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            text.append(alphabet[random.nextInt(alphabet.length)]);
        }
        final String content = text.toString();
        final LineIndex index = new LineIndex(content);

        final List<Integer> breakEnds = new ArrayList<>();
        final Matcher matcher = Pattern.compile("\\R").matcher(content);
        while (matcher.find()) {
            breakEnds.add(matcher.end());
        }
        for (int offset = 0; offset <= content.length(); offset++) {
            final int current = offset;
            final int expected = (int) breakEnds.stream().filter(end -> end <= current).count() + 1;
            Assertions.assertEquals(expected, index.lineNumberAt(offset), "offset " + offset);
        }
    }
}
