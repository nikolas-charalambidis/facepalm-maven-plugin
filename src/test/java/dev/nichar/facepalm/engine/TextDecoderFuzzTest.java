/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;

/**
 * Fuzzes {@link TextDecoder#decode} with random bytes (#178). It must never throw, must never return NUL characters,
 * and must decode printable ASCII unchanged.
 * <p>
 * {@code mvn test} runs it once, in regression mode. {@code JAZZER_FUZZ=1 mvn test -Dtest=TextDecoderFuzzTest} fuzzes
 * it locally, and ClusterFuzzLite calls {@link #fuzzerTestOneInput} in CI.
 */
class TextDecoderFuzzTest {

    @FuzzTest(maxDuration = "1m")
    void decode(final FuzzedDataProvider data) {
        fuzzerTestOneInput(data);
    }

    /**
     * Entry point for ClusterFuzzLite.
     */
    public static void fuzzerTestOneInput(final FuzzedDataProvider data) {
        final var bytes = data.consumeRemainingAsBytes();
        final var text = TextDecoder.decode(bytes);
        if (text == null) {
            return;
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalStateException("Decoded text contains a NUL character");
        }
        if (isPrintableAscii(bytes) && !text.equals(new String(bytes, StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Printable ASCII was not decoded unchanged");
        }
    }

    /**
     * Whether every byte is printable ASCII, a tab or a line break: text that every decoding path must keep as is.
     */
    private static boolean isPrintableAscii(final byte[] bytes) {
        for (final var b : bytes) {
            if (!(b >= 0x20 && b < 0x7F || b == '\t' || b == '\n' || b == '\r')) {
                return false;
            }
        }
        return true;
    }
}
