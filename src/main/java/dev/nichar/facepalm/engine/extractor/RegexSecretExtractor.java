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
            final var normalizedLine = rawLine.replace("\"", "").replace("'", "");

            for (final var sp : activePatterns) {
                if (sp.isMultiLine()) {
                    // Skip multi-line patterns to avoid redundant processing here.
                    continue;
                }
                final var m = sp.getPattern().matcher(normalizedLine);
                while (m.find()) {
                    // Extract group 1 if defined; otherwise return the full match.
                    final var secretValue = m.groupCount() >= 1 ? m.group(1) : m.group();
                    registerFinding(findings, localDedup, context, sp, secretValue, i + 1, rawLine);
                }
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
     * Initializes and registers a discovery finding if it passes local deduplication.
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
