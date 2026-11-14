/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards every default pattern against catastrophic backtracking (issue #243). A pattern that backtracks can hang a
 * build or a pre-commit hook on a single long line, as the unbounded gaps of issue #63 did.
 *
 * <p>
 * The check is deterministic instead of timing-based: the input is wrapped in a {@link CharSequence} that counts
 * {@code charAt} calls, every match is exhausted as the extractor does, and the count for an input of {@value #SHORT}
 * repetitions is compared with the count for {@value #LONG} repetitions. Linear matching grows the count by about
 * {@code LONG / SHORT}; quadratic matching grows it by about the square of that.
 */
class SecretPatternBacktrackingTest {

    /**
     * Repetitions of the input unit for the short input.
     */
    private static final int SHORT = 500;

    /**
     * Repetitions of the input unit for the long input.
     */
    private static final int LONG = 4_000;

    /**
     * Highest accepted growth of the {@code charAt} count from the short to the long input: twice the linear growth.
     * Quadratic matching grows by about {@code (LONG / SHORT)^2 = 64}.
     */
    private static final double MAX_GROWTH = 2.0 * LONG / SHORT;

    /**
     * Inputs applied to every pattern: separators, keywords and fragments that each pattern may start to match many
     * times on one line, including the minified-code and URI cases of issue #63.
     */
    private static final List<Input> GENERIC_INPUTS = List.of(
        new Input("", "a"),
        new Input("", "A"),
        new Input("", "0"),
        new Input("", " "),
        new Input("", ":"),
        new Input("", "="),
        new Input("", "."),
        new Input("", "-"),
        new Input("", "_"),
        new Input("", "/"),
        new Input("", "a:"),
        new Input("", "a."),
        new Input("", "a("),
        new Input("", "a,"),
        new Input("", "a.b(c,d);"),
        new Input("", "openai.apiKey(x);var y=1;"),
        new Input("", "heroku=x;"),
        new Input("", "mssql password "),
        new Input("", "token="),
        new Input("", "password="),
        new Input("", "api_key_secret_token_"),
        new Input("", "Authorization: Basic "),
        new Input("", "-----BEGIN PRIVATE KEY-----\n"),
        new Input("mongodb://", "a:"),
        new Input("postgres://", "a:"),
        new Input("mysql://", "a:"),
        new Input("redis://", "a:"),
        new Input("eyJ", "a."),
        new Input("token=a(", "b,"),
        new Input("token=", "a("),
        new Input("password=a(", "B_C,"),
        new Input("password=", "a.b"),
        new Input("token=", "a."),
        new Input("token=", "A_"),
        new Input("token=", "aBc"),
        new Input("token=a(", "aBc"),
        new Input("token=a(", "a.b("),
        new Input("token=a(b(", "C_D,"),
        new Input("-----BEGIN ", "A "),
        new Input("-----BEGIN PRIVATE KEY-----", "a"),
        new Input("", "-----BEGIN RSA PRIVATE KEY-----\n-----END EC PRIVATE KEY-----\n"));

    @ParameterizedTest
    @MethodSource("patterns")
    void pattern_matches_in_linear_time(final SecretPattern pattern) {
        for (final var input : inputs(pattern)) {
            final var shortCount = charReads(pattern.getPattern(), input.text(SHORT));
            final var longCount = charReads(pattern.getPattern(), input.text(LONG));

            assertThat((double) longCount / shortCount)
                .as("%s on %s: %d reads for %d repetitions, %d for %d", pattern.getName(), input, shortCount, SHORT,
                    longCount, LONG)
                .isLessThanOrEqualTo(MAX_GROWTH);
        }
    }

    /**
     * The guard itself must catch the kind of pattern fixed in issue #63.
     */
    @Test
    void guard_detects_unbounded_gap() {
        final var unbounded = Pattern.compile("(?i)heroku.*[=:]\\s*([0-9a-f]{8})");
        final var input = new Input("", "heroku=x;");

        final var growth = (double) charReads(unbounded, input.text(LONG)) / charReads(unbounded, input.text(SHORT));

        assertThat(growth).isGreaterThan(MAX_GROWTH);
    }

    static Stream<Arguments> patterns() {
        return SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .map(pattern -> Arguments.argumentSet(pattern.getName(), pattern));
    }

    /**
     * The generic inputs plus near-matches derived from the pattern's own samples: prefixes of the positive sample
     * repeated, and each prefix followed by a long run of the next character, which keeps the pattern matching without
     * reaching its end.
     */
    private static List<Input> inputs(final SecretPattern pattern) {
        final List<Input> inputs = new ArrayList<>(GENERIC_INPUTS);
        final var samples = SecretPatternSamplesTest.SAMPLES.get(pattern.getName());
        assertThat(samples).as("missing samples for %s", pattern.getName()).isNotNull();
        final var positive = samples[0];
        inputs.add(new Input("", positive));
        inputs.add(new Input("", samples[1]));
        final var length = positive.length();
        for (final int cut : new int[] {
            1, length / 4, length / 2, length * 3 / 4, length - 1 }) {
            final var prefix = positive.substring(0, Math.max(1, cut));
            inputs.add(new Input("", prefix));
            inputs.add(new Input(prefix, String.valueOf(positive.charAt(prefix.length()))));
        }
        return inputs;
    }

    /**
     * Number of characters the pattern reads to find every match in the text.
     */
    private static long charReads(final Pattern pattern, final String text) {
        final var counting = new CountingCharSequence(text);
        final var matcher = pattern.matcher(counting);
        while (matcher.find()) {
            // Exhaust all matches, as the extractor does.
        }
        return counting.reads;
    }

    /**
     * Test input: a prefix followed by a unit repeated a given number of times.
     */
    private record Input(String prefix, String unit) {

        String text(final int repetitions) {
            return prefix + unit.repeat(repetitions);
        }

        @Override
        public String toString() {
            final var shown = prefix.length() > 40 ? prefix.substring(0, 40) + "..." : prefix;
            return "'" + shown.replace("\n", "\\n") + "' + '" + unit.replace("\n", "\\n") + "' x n";
        }
    }

    /**
     * Character sequence that counts how often the regex engine reads a character.
     */
    private static final class CountingCharSequence implements CharSequence {

        private final String text;

        private long reads;

        CountingCharSequence(final String text) {
            this.text = text;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(final int index) {
            reads++;
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
