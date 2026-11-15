/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.pattern.SecretPattern;
import jakarta.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Scans file content using regular expressions to identify potential secrets.
 * Supports single-line matching and multi-line blocks like PEM certificates.
 */
@Named
@Singleton
public class RegexSecretExtractor implements SecretExtractor {

    @Inject
    private FacepalmConfig config;

    @Nonnull
    @Override
    public List<Finding> extract(@Nonnull final FileContext context) {
        final List<Finding> findings = new ArrayList<>();
        final Set<String> localDedup = new HashSet<>();
        final List<SecretPattern> activePatterns = config.getPatterns().getOverrides();

        // Single-Line Scanning.
        for (int i = 0; i < context.getLines().size(); i++) {
            final var rawLine = context.getLines().get(i);
            final var normalizedLine = SnippetMasker.normalize(rawLine);
            // Built on the first match only: most lines have none.
            int[] rawIndexes = null;

            // Collect every capture of the line first, so the snippet masks all of them, including values whose
            // finding is dropped as a duplicate (#218).
            final List<Capture> captures = new ArrayList<>();
            for (final var sp : activePatterns) {
                if (sp.isMultiLine()) {
                    // Skip multi-line patterns to avoid redundant processing here.
                    continue;
                }
                final var m = sp.getPattern().matcher(normalizedLine);
                while (m.find()) {
                    if (rawIndexes == null) {
                        rawIndexes = SnippetMasker.rawIndexes(rawLine);
                    }
                    final var group = m.groupCount() >= 1 && m.group(1) != null ? 1 : 0;
                    final var start = m.start(group);
                    final var end = endAtPlainQuote(rawLine, rawIndexes, start, m.end(group));
                    final var value = trimTrailingDelimiters(normalizedLine.substring(start, end));
                    if (!value.isEmpty()) {
                        captures.add(new Capture(sp, value, start, start + value.length()));
                    }
                }
            }
            if (captures.isEmpty()) {
                continue;
            }

            final var indexes = rawIndexes;
            final List<int[]> spans = captures.stream()
                .map(capture -> new int[] {
                    indexes[capture.start()], indexes[capture.end() - 1] + 1 })
                .toList();
            for (int c = 0; c < captures.size(); c++) {
                final var capture = captures.get(c);
                final var snippet = SnippetMasker.snippet(rawLine, spans, spans.get(c));
                registerFinding(findings, localDedup, context, capture.pattern(), capture.value(), i + 1, snippet);
            }
        }

        // Multi-Line Block Scanning (e.g., PEM certificates).
        // Built lazily on the first multi-line match, then shared by all patterns for this file.
        LineIndex lineIndex = null;
        for (final var sp : activePatterns) {
            if (!sp.isMultiLine()) {
                continue;
            }
            // Scan the entire file as a continuous block for multi-line patterns.
            final var matcher = sp.getPattern().matcher(context.getFullContent());
            while (matcher.find()) {
                final var secretValue = matcher.group();
                if (lineIndex == null) {
                    lineIndex = new LineIndex(context.getFullContent());
                }
                // Map character offset to its 1-based line number.
                final var lineNum = lineIndex.lineNumberAt(matcher.start());
                registerFinding(findings, localDedup, context, sp, secretValue, lineNum, "[MULTI-LINE BLOCK]");
            }
        }
        return findings;
    }

    /**
     * Returns capture group 1 if the pattern defines it and it participated in the match; otherwise the full match.
     * An optional group such as {@code (foo)?bar} can match without group 1, which must not yield a {@code null}
     * secret.
     */
    @Nonnull
    static String secretValue(@Nonnull final Matcher matcher) {
        final var group = matcher.groupCount() >= 1 ? matcher.group(1) : null;
        return group != null ? group : matcher.group();
    }

    /**
     * Removes the code around a value that a broad capture such as {@code [^'"\s}]{8,}} takes along: trailing
     * {@code ;} and {@code ,} ({@code password = "..." ;}, JSON {@code "password": "...",}) and closing parentheses
     * without a matching opening one ({@code connect(password=...)}).
     */
    @Nonnull
    static String trimTrailingDelimiters(@Nonnull final String value) {
        var end = value.length();
        var unmatchedClosing = 0;
        for (int i = 0; i < end; i++) {
            if (value.charAt(i) == '(') {
                unmatchedClosing--;
            } else if (value.charAt(i) == ')') {
                unmatchedClosing++;
            }
        }
        while (end > 0) {
            final var last = value.charAt(end - 1);
            if (last == ';' || last == ',') {
                end--;
            } else if (last == ')' && unmatchedClosing > 0) {
                unmatchedClosing--;
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    /**
     * End of a capture on the normalized line, cut at the first plain quote of the raw line inside it (#276), so a
     * capture never spans two quoted values.
     *
     * @param start Start of the capture on the normalized line.
     * @param end End of the capture on the normalized line.
     */
    private static int endAtPlainQuote(@Nonnull final String rawLine,
                                       @Nonnull final int[] rawIndexes,
                                       final int start,
                                       final int end) {
        if (start >= end) {
            return end;
        }
        final var rawEnd = rawIndexes[end - 1] + 1;
        final var quote = SnippetMasker.firstPlainQuote(rawLine, rawIndexes[start], rawEnd);
        if (quote == rawEnd) {
            return end;
        }
        // Normalized characters before the quote: the capture ends there.
        var cut = start;
        while (cut < end && rawIndexes[cut] < quote) {
            cut++;
        }
        return cut;
    }

    /**
     * Value captured by a single-line pattern and its span on the normalized line.
     */
    private record Capture(SecretPattern pattern, String value, int start, int end) {
    }

    /**
     * Initializes and registers a discovery finding if it passes local deduplication.
     *
     * @param snippet The source line with every secret on it masked, or a placeholder for multi-line blocks.
     */
    private void registerFinding(@Nonnull final List<Finding> findings,
                                 @Nonnull final Set<String> dedup,
                                 @Nonnull final FileContext ctx,
                                 @Nonnull final SecretPattern sp,
                                 @Nonnull final String value,
                                 final int lineNum,
                                 @Nonnull final String snippet) {

        // Use pattern name and secret value for file-level deduplication.
        final var hash = hashString(sp.getName() + value);
        final var existing = findings.stream().filter(f -> f.getDeduplicationHash().equals(hash)).findFirst();

        if (existing.isPresent()) {
            // One finding per file for the same secret value to reduce noise.
            return;
        }

        if (dedup.add(hash)) {
            final var f = Finding.builder()
                .patternName(sp.getName())
                .deduplicationHash(hash)
                .secretValue(value)
                .lineNumber(lineNum)
                .context(ctx)
                .contextSnippet(snippet.trim())
                .riskScore(sp.getBaseRisk())
                .confidenceScore(sp.getBaseConfidence())
                .build();
            f.log("Base Pattern Match", 0, 0);
            findings.add(f);
        }
    }

    /**
     * Generates a unique SHA-256 fingerprint for a discovery finding.
     */
    private String hashString(@Nonnull final String input) {
        try {
            final var messageDigest = MessageDigest.getInstance("SHA-256");
            final var hash = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            final var hexString = new StringBuilder();
            for (final var b : hash) {
                hexString.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
