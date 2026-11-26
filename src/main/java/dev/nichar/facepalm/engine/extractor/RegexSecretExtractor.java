/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.extractor;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.pattern.SecretPattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

            // Collect every capture of the line first, so the snippet masks all of them, including values whose
            // finding is dropped as a duplicate (#218). Patterns match on the raw line (#211): quotes stay visible,
            // and a capture's position is its position in the snippet.
            final List<Capture> captures = new ArrayList<>();
            for (final var sp : activePatterns) {
                if (sp.isMultiLine()) {
                    // Skip multi-line patterns to avoid redundant processing here.
                    continue;
                }
                final var m = sp.getPattern().matcher(rawLine);
                while (m.find()) {
                    final var group = secretGroup(m);
                    final var start = m.start(group);
                    // A capture never spans two quoted values (#276).
                    final var end = SnippetMasker.firstPlainQuote(rawLine, start, m.end(group));
                    final var value = trimTrailingDelimiters(rawLine.substring(start, end));
                    if (!value.isEmpty()) {
                        captures.add(new Capture(sp, value, start, start + value.length()));
                    }
                }
            }
            if (captures.isEmpty()) {
                continue;
            }

            final List<int[]> spans = captures.stream()
                .map(capture -> new int[] {
                    capture.start(), capture.end() })
                .toList();
            for (int c = 0; c < captures.size(); c++) {
                if (isShadowed(captures, c)) {
                    continue;
                }
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
        return mostSpecificPerValue(findings);
    }

    /**
     * Returns true if another capture on the line overlaps this one and comes from a more specific pattern: a higher
     * base confidence, or the same confidence and an earlier pattern. A generic assignment pattern then does not
     * report the value of an AWS key or a Slack token a second time (#290).
     */
    private static boolean isShadowed(@Nonnull final List<Capture> captures, final int index) {
        final var capture = captures.get(index);
        for (int other = 0; other < captures.size(); other++) {
            final var candidate = captures.get(other);
            if (other == index || candidate.start() >= capture.end() || capture.start() >= candidate.end()) {
                continue;
            }
            final var confidence = candidate.pattern().getBaseConfidence();
            final var ownConfidence = capture.pattern().getBaseConfidence();
            if (confidence > ownConfidence || confidence == ownConfidence && other < index) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps one finding per secret value in the file: the one from the pattern with the highest base confidence, or the
     * first of equally confident ones (#290). The order of the findings is kept.
     */
    @Nonnull
    private static List<Finding> mostSpecificPerValue(@Nonnull final List<Finding> findings) {
        final Map<String, Finding> best = new HashMap<>();
        for (final var finding : findings) {
            best.merge(finding.getSecretValue(), finding,
                (kept, next) -> next.getConfidenceScore() > kept.getConfidenceScore() ? next : kept);
        }
        return findings.stream().filter(finding -> best.get(finding.getSecretValue()) == finding).toList();
    }

    /**
     * Returns the first capture group that participated in the match; otherwise the full match. An optional group
     * such as {@code (foo)?bar} can match without group 1, which must not yield a {@code null} secret, and a pattern
     * with alternatives ({@code a=(x)|<a>(y)</a>}) captures the value in the group of the alternative that matched.
     */
    @Nonnull
    static String secretValue(@Nonnull final Matcher matcher) {
        return matcher.group(secretGroup(matcher));
    }

    /**
     * Index of the first capture group that participated in the match, or {@code 0} (the full match) if none did.
     */
    private static int secretGroup(@Nonnull final Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.start(group) >= 0) {
                return group;
            }
        }
        return 0;
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
     * Value captured by a single-line pattern and its span on the raw line.
     */
    private record Capture(SecretPattern pattern, String value, int start, int end) {
    }

    /**
     * Initializes and registers a discovery finding if it passes local deduplication.
     *
     * @param snippet The source line with every secret on it masked, or a placeholder for multi-line blocks.
     */
    @SuppressFBWarnings(value = "UNSAFE_HASH_EQUALS", justification = "The hash only deduplicates findings within a file; it is never compared with a secret.")
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
                .riskScore(0)
                .confidenceScore(0)
                .build();
            f.log("Base Pattern Match", sp.getBaseRisk(), sp.getBaseConfidence());
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
