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
