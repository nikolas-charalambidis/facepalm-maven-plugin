/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextDecoderTest {

    private static final String TEXT = "# Passwort für DB\ndb.password=s3cr3t";

    @Test
    void decodes_utf8() {
        assertEquals(TEXT, TextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void falls_back_to_iso_8859_1_for_invalid_utf8() {
        // Issue #64: a Latin-1 .properties file was silently not scanned.
        assertEquals(TEXT, TextDecoder.decode(TEXT.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void honours_byte_order_marks() {
        assertEquals(TEXT, TextDecoder.decode(concat(new byte[] {
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
            TEXT.getBytes(StandardCharsets.UTF_8))));
        assertEquals(TEXT, TextDecoder.decode(concat(new byte[] {
            (byte) 0xFF, (byte) 0xFE },
            TEXT.getBytes(StandardCharsets.UTF_16LE))));
        assertEquals(TEXT, TextDecoder.decode(concat(new byte[] {
            (byte) 0xFE, (byte) 0xFF },
            TEXT.getBytes(StandardCharsets.UTF_16BE))));
    }

    @Test
    void treats_content_with_many_control_bytes_as_binary() {
        final var binary = new byte[512];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i % 7 == 0 ? 'x' : i % 32);
        }

        assertNull(TextDecoder.decode(binary));
    }

    /**
     * Issue #219: a single NUL byte made a text file binary, so it was not scanned.
     */
    @Test
    void decodes_text_with_isolated_nul_bytes_and_keeps_line_layout() {
        final var bytes = (TEXT + "\n\0\n").getBytes(StandardCharsets.UTF_8);

        assertEquals(TEXT + "\n \n", TextDecoder.decode(bytes));
    }

    /**
     * Issue #219: UTF-16 without a byte order mark was treated as binary because of its NUL bytes.
     */
    @Test
    void decodes_utf16_without_byte_order_mark() {
        assertEquals(TEXT, TextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(TEXT, TextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_16BE)));
    }

    private static byte[] concat(final byte[] head, final byte[] tail) {
        final var result = new byte[head.length + tail.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(tail, 0, result, head.length, tail.length);
        return result;
    }
}
