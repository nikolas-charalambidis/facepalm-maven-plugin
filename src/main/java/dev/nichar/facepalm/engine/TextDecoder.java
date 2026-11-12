/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decodes file content for scanning without silently dropping files in legacy encodings.
 *
 * <ol>
 * <li>A UTF-8 or UTF-16 byte order mark selects that encoding.</li>
 * <li>Content containing a NUL byte is treated as binary and not scanned.</li>
 * <li>Valid UTF-8 is decoded as UTF-8.</li>
 * <li>Anything else falls back to ISO-8859-1, which maps every byte, so files such as Latin-1
 * {@code .properties} (the Java default before Java 9) are still scanned.</li>
 * </ol>
 */
final class TextDecoder {

    private TextDecoder() {
    }

    /**
     * Returns the decoded text, or {@code null} if the content is binary.
     */
    @Nullable
    static String decode(@Nonnull final byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        for (final byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
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
