/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decodes file content for scanning without silently dropping text files.
 *
 * <ol>
 * <li>A UTF-8 or UTF-16 byte order mark selects that encoding.</li>
 * <li>UTF-16 without a byte order mark is recognized by its NUL bytes: mostly ASCII text in UTF-16 has a NUL in
 * every other byte, at odd positions for little endian and at even positions for big endian.</li>
 * <li>Content in which control bytes make up more than {@value #MAX_CONTROL_PERCENT} % of the first
 * {@value #SAMPLE_SIZE} bytes is binary and not scanned. A few NUL bytes in a text file do not make it binary, so they
 * cannot hide a secret (#219).</li>
 * <li>Valid UTF-8 is decoded as UTF-8.</li>
 * <li>Anything else falls back to ISO-8859-1, which maps every byte, so files such as Latin-1
 * {@code .properties} (the Java default before Java 9) are still scanned.</li>
 * </ol>
 * NUL characters in decoded text become spaces, so they separate tokens like whitespace without shifting lines or
 * columns.
 */
final class TextDecoder {

    /**
     * Number of leading bytes inspected to detect UTF-16 without a byte order mark and binary content.
     */
    private static final int SAMPLE_SIZE = 8192;

    /**
     * Highest share of control bytes, in percent of the sample, that text may contain. Random binary data has about
     * 10 %, text close to 0 %.
     */
    private static final int MAX_CONTROL_PERCENT = 5;

    /**
     * Lowest share of code units, in percent, that must have a NUL in the high byte for UTF-16 without a byte order
     * mark.
     */
    private static final int MIN_UTF16_NUL_PERCENT = 30;

    private TextDecoder() {
    }

    /**
     * Returns the decoded text, or {@code null} if the content is binary.
     */
    @Nullable
    static String decode(@Nonnull final byte[] bytes) {
        final String text;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        } else if (startsWith(bytes, 0xFE, 0xFF)) {
            text = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        } else if (startsWith(bytes, 0xFF, 0xFE)) {
            text = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        } else {
            final var utf16 = decodeUtf16WithoutBom(bytes);
            if (utf16 != null) {
                text = utf16;
            } else if (isBinary(bytes)) {
                return null;
            } else {
                final var utf8 = decodeStrictly(bytes, StandardCharsets.UTF_8);
                text = utf8 != null ? utf8 : new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        return text.replace('\0', ' ');
    }

    /**
     * Decodes UTF-16 without a byte order mark, recognized by NUL bytes in every other position of the sample, or
     * returns {@code null}.
     */
    @Nullable
    private static String decodeUtf16WithoutBom(@Nonnull final byte[] bytes) {
        final var sample = Math.min(bytes.length, SAMPLE_SIZE) & ~1;
        if (sample < 4) {
            return null;
        }
        var evenNuls = 0;
        var oddNuls = 0;
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                if (i % 2 == 0) {
                    evenNuls++;
                } else {
                    oddNuls++;
                }
            }
        }
        final var units = sample / 2;
        if (isUtf16Pattern(oddNuls, evenNuls, units)) {
            return decodeStrictly(bytes, StandardCharsets.UTF_16LE);
        }
        if (isUtf16Pattern(evenNuls, oddNuls, units)) {
            return decodeStrictly(bytes, StandardCharsets.UTF_16BE);
        }
        return null;
    }

    /**
     * Whether NUL bytes sit in the high-byte position of many code units and almost never in the low-byte position.
     */
    private static boolean isUtf16Pattern(final int highByteNuls, final int lowByteNuls, final int units) {
        return highByteNuls * 100 >= units * MIN_UTF16_NUL_PERCENT && lowByteNuls * 10 <= highByteNuls;
    }

    /**
     * Whether control bytes other than whitespace and escape make up more than {@value #MAX_CONTROL_PERCENT} % of the
     * sample.
     */
    private static boolean isBinary(@Nonnull final byte[] bytes) {
        final var sample = Math.min(bytes.length, SAMPLE_SIZE);
        var controls = 0;
        for (int i = 0; i < sample; i++) {
            final var b = bytes[i];
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != 0x0B && b != '\f' && b != '\r' && b != 0x1B) {
                controls++;
            }
        }
        return controls * 100 > sample * MAX_CONTROL_PERCENT;
    }

    /**
     * Decodes the bytes, or returns {@code null} if they are not valid in the charset.
     */
    @Nullable
    private static String decodeStrictly(@Nonnull final byte[] bytes, @Nonnull final Charset charset) {
        try {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean startsWith(@Nonnull final byte[] bytes, final int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        final var head = new byte[prefix.length];
        for (int i = 0; i < prefix.length; i++) {
            head[i] = (byte) prefix[i];
        }
        return Arrays.equals(Arrays.copyOf(bytes, prefix.length), head);
    }
}
