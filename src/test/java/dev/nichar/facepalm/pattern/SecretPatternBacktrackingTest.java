/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * {@code charAt} calls, and every match is exhausted as the extractor does. Each input is a unit repeated to about
 * {@value #BASE_LENGTH}, twice and four times as many characters. The guard compares the reads per added character
 * from the first to the second size with the same from the second to the third: linear matching reads the same
 * number per character at any length, so constant costs cancel out and even a small quadratic term shows as growth.
 * Exponential matching stops at a read budget instead of hanging the build.
 */
class SecretPatternBacktrackingTest {

    /**
     * Approximate length of the shortest input in characters; the other two are twice and four times as long.
     * Longer than every bound in the patterns ({@code {1,256}}), so bounded repetitions already behave linearly.
     */
    private static final int BASE_LENGTH = 1_000;

    /**
     * Lowest number of repetitions of the unit for the shortest input. A long unit, such as a whole sample, repeated
     * only a few times gives a noisy read count.
     */
    private static final int MIN_REPETITIONS = 64;

    /**
     * Highest accepted ratio of the reads per added character on the longer step to the shorter step. Linear
     * matching gives 1; quadratic matching gives up to 2.
     */
    private static final double MAX_ACCELERATION = 1.1;

    /**
     * Highest accepted number of character reads per input character. Far above any linear pattern, so only runaway
     * (exponential or heavily polynomial) matching reaches it, and it fails fast instead of hanging the build.
     */
    private static final long READS_PER_CHAR_BUDGET = 5_000;

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

    /**
     * Splits a sample into tokens: runs of letters and digits, and every other character on its own.
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]+|[^A-Za-z0-9]");

    @ParameterizedTest
    @MethodSource("patterns")
    void pattern_matches_in_linear_time(final SecretPattern pattern) {
        for (final var input : inputs(pattern)) {
            assertLinear(pattern.getPattern(), input, pattern.getName());
        }
    }

    /**
     * The guard must catch the kind of pattern fixed in issue #63.
     */
    @Test
    void guard_detects_unbounded_gap() {
        final var unbounded = Pattern.compile("(?i)heroku.*[=:]\\s*([0-9a-f]{8})");

        assertThat(acceleration(unbounded, new Input("", "heroku=x;"))).isGreaterThan(MAX_ACCELERATION);
    }

    /**
     * The guard must catch a quadratic term even when a large linear cost hides it in the overall growth: here every
     * letter scans up to 300 characters, and only every {@code heroku} scans to the end of the line.
     */
    @Test
    void guard_detects_quadratic_term_behind_linear_cost() {
        final var mixed = Pattern.compile("(?i)(?:[a-z].{0,300}?#|heroku.*[=:])");
        final var input = new Input("", "heroku" + "z".repeat(14));
        final var reps = repetitions(input);

        final var growth = (double) charReads(mixed, input.text(4 * reps)) / charReads(mixed, input.text(reps));

        assertThat(growth).as("overall growth looks linear").isLessThan(2.0 * 4);
        assertThat(acceleration(mixed, input)).isGreaterThan(MAX_ACCELERATION);
    }

    /**
     * Exponential matching must fail at the read budget instead of hanging the build. The repetition is bounded on
     * purpose: the JDK short-circuits classic unbounded cases such as {@code (a+)+b}, but not bounded ones, which is
     * how the generic patterns hung during #243.
     */
    @Test
    void guard_stops_exponential_matching() {
        final var exponential = Pattern.compile("(?:a+){1,64}b");

        assertThatThrownBy(() -> charReads(exponential, "a".repeat(BASE_LENGTH)))
            .isInstanceOf(ReadBudgetExceededException.class);
    }

    static Stream<Arguments> patterns() {
        return SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .map(pattern -> Arguments.argumentSet(pattern.getName(), pattern));
    }

    /**
     * The generic inputs plus near-matches derived from the pattern's own samples:
     * <ul>
     * <li>prefixes of the positive sample repeated, and each prefix followed by a long run of the next character,
     * which keeps the pattern matching without reaching its end;</li>
     * <li>every run of one to three tokens of the positive sample repeated, alone and after the part of the sample
     * before it, such as {@code mongodb://} followed by {@code admin:} many times.</li>
     * </ul>
     */
    private static Set<Input> inputs(final SecretPattern pattern) {
        final Set<Input> inputs = new LinkedHashSet<>(GENERIC_INPUTS);
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
        final List<Integer> starts = new ArrayList<>();
        final var tokens = TOKEN.matcher(positive);
        while (tokens.find()) {
            starts.add(tokens.start());
        }
        starts.add(length);
        for (int first = 0; first < starts.size() - 1; first++) {
            for (int last = first + 1; last <= Math.min(first + 3, starts.size() - 1); last++) {
                final var unit = positive.substring(starts.get(first), starts.get(last));
                inputs.add(new Input("", unit));
                inputs.add(new Input(positive.substring(0, starts.get(first)), unit));
            }
        }
        return inputs;
    }

    private static void assertLinear(final Pattern pattern, final Input input, final String name) {
        assertThat(acceleration(pattern, input))
            .as("%s on %s: reads per added character grow on longer input", name, input)
            .isLessThanOrEqualTo(MAX_ACCELERATION);
    }

    /**
     * Ratio of the reads per added repetition from two to four base lengths to the same from one to two base lengths.
     * The {@code + 1} keeps patterns that read almost nothing (a literal prefix that never occurs) from dividing by
     * a near-zero cost.
     */
    private static double acceleration(final Pattern pattern, final Input input) {
        final var reps = repetitions(input);
        final var single = charReads(pattern, input.text(reps));
        final var twice = charReads(pattern, input.text(2 * reps));
        final var fourTimes = charReads(pattern, input.text(4 * reps));
        final var first = (double) (twice - single) / reps;
        final var second = (double) (fourTimes - twice) / (2 * reps);
        return (second + 1) / (first + 1);
    }

    /**
     * Repetitions of the unit for the shortest input: about {@value #BASE_LENGTH} characters, and at least
     * {@value #MIN_REPETITIONS} so that the position where matches start and end in a long unit evens out.
     */
    private static int repetitions(final Input input) {
        return Math.max(MIN_REPETITIONS, BASE_LENGTH / input.unit().length());
    }

    /**
     * Number of characters the pattern reads to find every match in the text.
     *
     * @throws ReadBudgetExceededException if the pattern reads more than {@value #READS_PER_CHAR_BUDGET} characters
     * per input character
     */
    private static long charReads(final Pattern pattern, final String text) {
        final var counting = new CountingCharSequence(text, READS_PER_CHAR_BUDGET * Math.max(1, text.length()));
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
     * Thrown when a pattern exceeds the read budget, which only runaway matching does.
     */
    private static final class ReadBudgetExceededException extends RuntimeException {

        /**
         * Serialization version of this exception.
         */
        private static final long serialVersionUID = 1L;

        ReadBudgetExceededException(final long budget) {
            super("more than " + budget + " character reads: runaway backtracking");
        }
    }

    /**
     * Character sequence that counts how often the regex engine reads a character and stops it at a budget.
     */
    private static final class CountingCharSequence implements CharSequence {

        private final String text;

        private final long budget;

        private long reads;

        CountingCharSequence(final String text, final long budget) {
            this.text = text;
            this.budget = budget;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(final int index) {
            if (++reads > budget) {
                throw new ReadBudgetExceededException(budget);
            }
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
