/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Recognizes captured values that cannot be secrets (#386): references to a secret in a templating syntax,
 * predictable dummy values, and format descriptions or code expressions that a pattern matched as a value.
 * The evaluators discount such values like a {@code ${VAR}} placeholder.
 */
@UtilityClass
@SuppressFBWarnings(value = "REDOS", justification = "False positive: only possessive quantifiers, which cannot backtrack.")
public class ValueClassifier {

    /**
     * Identifier, or a path of identifiers joined by {@code .}, {@code :} or {@code /}, such as
     * {@code vault.payments.token}: the inside of a reference.
     */
    private static final String PATH = "[A-Za-z_][\\w-]*+(?:[.:/][A-Za-z_][\\w-]*+)*+";

    /**
     * A whole value that refers to a secret instead of containing one:
     * <ul>
     * <li>a path in braces after one or two sigils: {@code #{...}} (Spring, Ruby), {@code @{...}} (MSBuild,
     * PowerShell),
     * {@code ~{...}~} and other delimiter pairs of deployment tools. The closing brace is optional, as the generic
     * patterns end a value before it;</li>
     * <li>a path in parentheses, brackets or angle brackets, optionally after a sigil: {@code $(VAR)} (Kubernetes,
     * Make);</li>
     * <li>{@code %(name)s} (Python) and {@code <%= ... %>} (ERB).</li>
     * </ul>
     * Plain {@code {...}} is not a reference: Maven encrypts passwords as {@code {base64}}.
     */
    private static final Pattern REFERENCE = Pattern.compile(
        "(?:[^\\w\\s{(\\[<]{1,2}+\\{" + PATH + "}?+|[^\\w\\s{(\\[<]{0,2}+(?:\\(" + PATH + "\\)|\\[" + PATH + "]|<"
            + PATH
            + ">))[^\\w\\s]{0,2}+|%\\(" + PATH + "\\)[a-z]|<%[=-]?+[^%]*+%>");

    /**
     * Code inside a value: a method call ({@code .split(}) or an index after a call ({@code )[1]}), as in a script
     * stored in a JSON file.
     */
    private static final Pattern CODE = Pattern.compile("\\.[A-Za-z_]\\w*+\\(|\\)\\[\\d++]");

    /**
     * A format description such as the {@code hostname:port:database:username:password} header of a pgpass file: at
     * least three lower-case words joined by {@code :}. Starts only at a word start, so a search cannot restart
     * inside every word.
     */
    private static final Pattern DESCRIPTOR = Pattern.compile("(?<![a-z])[a-z]++(?::[a-z]++){2,}+");

    /**
     * Comment markers removed from the start of a token before it is compared with {@link #DESCRIPTOR}.
     */
    private static final Pattern LEADING_COMMENT = Pattern.compile("^(?:#|;|//|--)++");

    /**
     * Every pattern of this class, for the backtracking guard.
     */
    static final List<Pattern> PATTERNS = List.of(REFERENCE, CODE, DESCRIPTOR, LEADING_COMMENT);

    /**
     * Letters and digits in keyboard order, row by row, so that a keyboard walk such as {@code qwerty} or
     * {@code asdfgh} is a run of neighbors.
     */
    private static final String KEYBOARD = "1234567890qwertyuiopasdfghjklzxcvbnm";

    /**
     * Shortest value checked for predictability: shorter values are too short for a pattern to tell.
     */
    private static final int MIN_PREDICTABLE_LENGTH = 6;

    /**
     * Shortest run of neighbors, and shortest letter or digit sequence, that counts as predictable.
     */
    private static final int MIN_RUN = 3;

    /**
     * Longest unit that counts as a short repeated unit, such as {@code abc} in {@code abcabcabc}.
     */
    private static final int MAX_UNIT = 8;

    /**
     * Whether the whole value refers to a secret in a templating syntax, such as {@code #{vault.token}} or
     * {@code $(DB_PASSWORD)}.
     */
    public static boolean isReference(@Nonnull final String value) {
        return REFERENCE.matcher(value).matches();
    }

    /**
     * Whether the value is a predictable dummy: one character or a short unit repeated ({@code AAAA},
     * {@code abcabc}), runs of consecutive characters or keyboard neighbors ({@code 0123456789abcdef},
     * {@code qwerty}), or letters and digits that each count up or down ({@code A1b2C3d4}).
     */
    public static boolean isPredictable(@Nonnull final String value) {
        if (value.length() < MIN_PREDICTABLE_LENGTH) {
            return false;
        }
        final var lower = value.toLowerCase(Locale.ROOT);
        return isRepeatedUnit(lower) || isMadeOfRuns(lower) || isInterleavedSequence(lower);
    }

    /**
     * Whether the value is code or a format description instead of a value: a method call or index
     * ({@code responseHeader.split(" ")[1]}), or a token of the line that lists lower-case words joined by
     * {@code :} ({@code #hostname:port:database:username:password}).
     *
     * @param line The source line the value was found on.
     */
    public static boolean isNotAValue(@Nonnull final String value, @Nonnull final String line) {
        return CODE.matcher(value).find() || DESCRIPTOR.matcher(
            LEADING_COMMENT.matcher(tokenAround(value, line)).replaceFirst("")).matches();
    }

    /**
     * Returns the whitespace- and quote-delimited token of the line that contains the value, or the value itself if
     * the line does not contain it.
     */
    @Nonnull
    private static String tokenAround(@Nonnull final String value, @Nonnull final String line) {
        final var at = line.indexOf(value);
        if (at < 0) {
            return value;
        }
        int from = at;
        while (from > 0 && !isTokenEnd(line.charAt(from - 1))) {
            from--;
        }
        int to = at + value.length();
        while (to < line.length() && !isTokenEnd(line.charAt(to))) {
            to++;
        }
        return line.substring(from, to);
    }

    private static boolean isTokenEnd(final char c) {
        return Character.isWhitespace(c) || c == '"' || c == '\'';
    }

    /**
     * Whether the value is a unit of at most {@value #MAX_UNIT} characters and half its length repeated, possibly
     * ending in part of the unit.
     */
    private static boolean isRepeatedUnit(@Nonnull final String value) {
        for (int unit = 1; unit <= Math.min(MAX_UNIT, value.length() / 2); unit++) {
            if (repeats(value, unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean repeats(@Nonnull final String value, final int unit) {
        for (int i = unit; i < value.length(); i++) {
            if (value.charAt(i) != value.charAt(i - unit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the value splits into runs of at least {@value #MIN_RUN} characters, each a neighbor of the previous
     * one: the same character, the next or previous letter or digit, or a keyboard neighbor.
     */
    private static boolean isMadeOfRuns(@Nonnull final String value) {
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            if (neighbors(value.charAt(i - 1), value.charAt(i))) {
                run++;
            } else if (run < MIN_RUN) {
                return false;
            } else {
                run = 1;
            }
        }
        return run >= MIN_RUN;
    }

    private static boolean neighbors(final char a, final char b) {
        if (a == b || isStep(a, b)) {
            return true;
        }
        final var at = KEYBOARD.indexOf(a);
        return at >= 0 && (at + 1 < KEYBOARD.length() && KEYBOARD.charAt(at + 1) == b
            || at > 0 && KEYBOARD.charAt(at - 1) == b);
    }

    /**
     * Whether {@code b} follows or precedes {@code a} among the digits or among the letters, wrapping from {@code 9} to
     * {@code 0} and from {@code z} to {@code a}.
     */
    private static boolean isStep(final char a, final char b) {
        if (Character.isDigit(a) && Character.isDigit(b)) {
            final var step = Math.floorMod(b - a, 10);
            return step == 1 || step == 9;
        }
        if (isLetter(a) && isLetter(b)) {
            final var step = Math.floorMod(b - a, 26);
            return step == 1 || step == 25;
        }
        return false;
    }

    private static boolean isLetter(final char c) {
        return c >= 'a' && c <= 'z';
    }

    /**
     * Whether the value has only letters and digits, and both its letters and its digits are sequences of at least
     * {@value #MIN_RUN} where each one follows or precedes the one before, as in {@code A1b2C3d4}.
     */
    private static boolean isInterleavedSequence(@Nonnull final String value) {
        final var letters = new StringBuilder();
        final var digits = new StringBuilder();
        for (final var c : value.toCharArray()) {
            if (isLetter(c)) {
                letters.append(c);
            } else if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                return false;
            }
        }
        return isSequence(letters) && isSequence(digits);
    }

    private static boolean isSequence(@Nonnull final CharSequence chars) {
        if (chars.length() < MIN_RUN) {
            return false;
        }
        for (int i = 1; i < chars.length(); i++) {
            if (!isStep(chars.charAt(i - 1), chars.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
