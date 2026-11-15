/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import dev.nichar.facepalm.engine.Finding;
import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Masks every secret on a source line before the line becomes a finding's context snippet (#218). The snippet is
 * written to the JSON findings, the reports and the log, so it must never contain a raw secret.
 * <p>
 * Patterns match on the line with quotes removed, so a captured value is located by position on that line and mapped
 * back to the raw line instead of being searched for as text: a secret containing quotes or followed by {@code ;} is
 * then still found. Each masked span is widened to the whole token around it, so a pattern that captures only part
 * of a token does not reveal the rest. Long tokens mixing letters and digits are masked as well, because they are
 * likely secrets that no pattern detected (a second credential on the same line).
 */
@UtilityClass
class SnippetMasker {

    /**
     * Whole token of at least {@value #MIN_MIXED_TOKEN} base64 or base64url characters, with optional padding. Masked
     * when it mixes letters and digits ({@link #isMixed}): likely a secret even if no pattern detected it.
     * Identifiers, words and version numbers are shorter or lack the mix. Possessive and anchored at the token start,
     * so it matches in linear time.
     */
    private static final Pattern LONG_TOKEN = Pattern.compile(
        "(?<![A-Za-z0-9+/_\\-])[A-Za-z0-9+/_\\-]{" + SnippetMasker.MIN_MIXED_TOKEN + ",}+={0,2}");

    /**
     * Minimum length of a token masked for mixing letters and digits.
     */
    private static final int MIN_MIXED_TOKEN = 20;

    /**
     * Most {@code =} padding characters added to a widened span.
     */
    private static final int MAX_PADDING = 2;

    /**
     * Characters of context kept on each side of a finding's value in its snippet (#276). The snippet used to be the
     * whole line, which on minified files copied the entire file into every finding.
     */
    private static final int WINDOW = 60;

    /**
     * Marks a snippet cut short at either end.
     */
    private static final String ELLIPSIS = "...";

    /**
     * Characters that end a fragment at the edge of a snippet window: whitespace and the punctuation around values.
     */
    private static final String FRAGMENT_DELIMITERS = " \t\"',;&{}()[]<>";

    /**
     * Value inside the snippet window that is masked even if no pattern detected it (#276): the content of a quoted
     * string ({@code "..."}, {@code '...'}, with escapes and doubled quotes), or an unquoted value after {@code =} or
     * {@code :}. Context such as keys, calls and operators stays readable. The loops are unrolled, so groups repeat
     * only
     * per escape or doubled quote and a long string cannot overflow the stack (#243).
     */
    private static final Pattern VALUE = Pattern.compile(
        "\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"|'([^']*+(?:''[^']*+)*+)'|[=:]\\s*+([^\\s\"',;&{}()\\[\\]<>]++)");

    /**
     * Shortest value that the snippet masks: shorter ones (flags, numbers, single words) carry no secret.
     */
    private static final int MIN_MASKED_VALUE = 4;

    /**
     * Removes the quotes that patterns do not see.
     */
    @Nonnull
    static String normalize(@Nonnull final String rawLine) {
        return rawLine.replace("\"", "").replace("'", "");
    }

    /**
     * Maps every index of the {@link #normalize normalized} line to its index in the raw line.
     */
    @Nonnull
    static int[] rawIndexes(@Nonnull final String rawLine) {
        final var indexes = new int[normalize(rawLine).length()];
        int normalized = 0;
        for (int raw = 0; raw < rawLine.length(); raw++) {
            final var c = rawLine.charAt(raw);
            if (c != '"' && c != '\'') {
                indexes[normalized++] = raw;
            }
        }
        return indexes;
    }

    /**
     * Returns the raw line with every given span and every mixed token replaced by its masked form.
     *
     * @param rawLine The source line as read from the file.
     * @param spans Raw-line spans ({@code [start, end)}) of the captured secrets.
     */
    @Nonnull
    static String mask(@Nonnull final String rawLine, @Nonnull final List<int[]> spans) {
        final List<int[]> all = new ArrayList<>();
        for (final var span : spans) {
            all.add(widenToToken(rawLine, span[0], span[1]));
        }
        final var tokens = LONG_TOKEN.matcher(rawLine);
        while (tokens.find()) {
            if (isMixed(tokens.group())) {
                all.add(new int[] {
                    tokens.start(), tokens.end() });
            }
        }
        if (all.isEmpty()) {
            return rawLine;
        }
        all.sort(Comparator.comparingInt(span -> span[0]));

        final var masked = new StringBuilder(rawLine.length());
        int copied = 0;
        int start = all.get(0)[0];
        int end = all.get(0)[1];
        for (final var span : all.subList(1, all.size())) {
            if (span[0] <= end) {
                end = Math.max(end, span[1]);
            } else {
                masked.append(rawLine, copied, start).append(Finding.mask(rawLine.substring(start, end)));
                copied = end;
                start = span[0];
                end = span[1];
            }
        }
        return masked.append(rawLine, copied, start).append(Finding.mask(rawLine.substring(start, end)))
            .append(rawLine, end, rawLine.length()).toString();
    }

    /**
     * Returns the snippet of one finding (#276): a window of about {@value #WINDOW} characters on each side of its
     * value, with every captured secret, every long mixed token and every quoted or assigned value in it masked.
     * Fragments cut by the window edges are dropped, so a value is never shown in part.
     *
     * @param rawLine The source line as read from the file.
     * @param spans Raw-line spans ({@code [start, end)}) of all secrets captured on the line.
     * @param focus Raw-line span of this finding's value.
     */
    @Nonnull
    static String snippet(@Nonnull final String rawLine, @Nonnull final List<int[]> spans, @Nonnull final int[] focus) {
        int from = Math.max(0, focus[0] - WINDOW);
        int to = Math.min(rawLine.length(), focus[1] + WINDOW);
        if (from > 0) {
            int next = from;
            while (next < focus[0] && FRAGMENT_DELIMITERS.indexOf(rawLine.charAt(next)) < 0) {
                next++;
            }
            from = next;
        }
        if (to < rawLine.length()) {
            int previous = to;
            while (previous > focus[1] && FRAGMENT_DELIMITERS.indexOf(rawLine.charAt(previous - 1)) < 0) {
                previous--;
            }
            to = previous;
        }
        final var window = rawLine.substring(from, to);

        final List<int[]> windowSpans = new ArrayList<>();
        for (final var span : spans) {
            final var start = Math.max(span[0], from) - from;
            final var end = Math.min(span[1], to) - from;
            if (start < end) {
                windowSpans.add(new int[] {
                    start, end });
            }
        }
        final var values = VALUE.matcher(window);
        while (values.find()) {
            for (int group = 1; group <= values.groupCount(); group++) {
                if (values.group(group) != null && values.group(group).length() >= MIN_MASKED_VALUE) {
                    windowSpans.add(new int[] {
                        values.start(group), values.end(group) });
                }
            }
        }
        return (from > 0 ? ELLIPSIS : "") + mask(window, windowSpans) + (to < rawLine.length() ? ELLIPSIS : "");
    }

    /**
     * Index in the raw line where a capture must end at the latest (#276): the first plain quote inside its raw span.
     * Patterns match on the line without quotes, so on minified JSON a broad capture can run from one quoted value into
     * the next; its masked form would then show the end of another secret. Doubled ({@code ''}) and escaped
     * ({@code \"}) quotes belong to the value and do not end it.
     *
     * @return The raw index of the first plain quote in {@code [start, end)}, or {@code end} if there is none.
     */
    static int firstPlainQuote(@Nonnull final String rawLine, final int start, final int end) {
        for (int i = start; i < end; i++) {
            final var c = rawLine.charAt(i);
            if (c != '"' && c != '\'') {
                continue;
            }
            final var escaped = i > 0 && rawLine.charAt(i - 1) == '\\';
            final var doubled = i + 1 < rawLine.length() && rawLine.charAt(i + 1) == c
                || i > 0 && rawLine.charAt(i - 1) == c;
            if (!escaped && !doubled) {
                return i;
            }
        }
        return end;
    }

    /**
     * Widens a span to the whole token around it: base64 and base64url characters on both sides, plus trailing
     * {@code =} padding. A leading {@code =} is not included, as it usually separates a key from its value.
     */
    @Nonnull
    private static int[] widenToToken(@Nonnull final String line, final int start, final int end) {
        int from = start;
        while (from > 0 && isTokenChar(line.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < line.length() && isTokenChar(line.charAt(to))) {
            to++;
        }
        for (int padding = 0; padding < MAX_PADDING && to < line.length() && line.charAt(to) == '='; padding++) {
            to++;
        }
        return new int[] {
            from, to };
    }

    /**
     * Whether the token contains both a letter and a digit.
     */
    private static boolean isMixed(@Nonnull final String token) {
        return token.chars().anyMatch(Character::isDigit) && token.chars().anyMatch(Character::isLetter);
    }

    private static boolean isTokenChar(final char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/'
            || c == '_' || c == '-';
    }
}
