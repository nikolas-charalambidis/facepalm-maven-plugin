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
    void treats_nul_bytes_as_binary() {
        assertNull(TextDecoder.decode(new byte[] {
            'a', 0, 'b' }));
    }

    private static byte[] concat(final byte[] head, final byte[] tail) {
        final var result = new byte[head.length + tail.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(tail, 0, result, head.length, tail.length);
        return result;
    }
}
