/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.FacepalmConfig;
import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Applies inline suppression markers (#12). {@code facepalm:ignore} anywhere on a line suppresses the findings on that
 * line, and {@code facepalm:ignore-next-line} the findings on the next line. The marker is found as text, not parsed as
 * a comment, so it works after {@code //}, {@code #}, {@code <!-- -->}, {@code --} or {@code ;} alike. Text after the
 * marker is its optional reason.
 * <p>
 * One marker suppresses one line, never a block or a file. Suppressed findings stay in the reports as
 * {@link Severity#SUPPRESSED}. A marker whose line has no finding is reported as an {@value #UNUSED} warning, so a
 * stale marker cannot silently hide a secret added to that line later.
 */
@Named
@Singleton
public class InlineSuppressor {

    /**
     * Pattern name of the warning for a marker whose line has no finding.
     */
    public static final String UNUSED = "Unused Suppression";

    /**
     * Text every marker contains: a cheap check before the regex, since most lines have no marker.
     */
    private static final String MARKER_TEXT = "facepalm:ignore";

    /**
     * Marker as a whole word ({@code facepalm:ignored} is not one); group 1 is {@code -next-line} if present, group 2
     * the rest of the line.
     */
    private static final Pattern MARKER = Pattern.compile("(?<![\\w:-])facepalm:ignore(-next-line)?(?![\\w-])(.*)");

    /**
     * Comment closer at the end of the line ({@code -->}, {@code * /}, {@code %>}, {@code #}}), not part of the reason.
     * Removed before the separators, so the {@code --} of {@code -->} is not taken for one.
     */
    private static final Pattern COMMENT_CLOSER = Pattern.compile("\\s*(?:-->|\\*/|%>|#})\\s*$");

    /**
     * Separators between the marker and its reason.
     */
    private static final Pattern SEPARATOR = Pattern.compile("^[\\s:=-]+");

    /**
     * Longest reason kept; the rest of a long line is cut.
     */
    private static final int MAX_REASON = 200;

    @Inject
    private FacepalmConfig config;

    /**
     * Suppresses the findings that a marker in the file targets and returns an {@value #UNUSED} warning for every
     * marker whose line has no finding. Does nothing when {@code suppressions} is off.
     *
     * @param findings All findings of the file, after scoring.
     * @return The warnings for unused markers, to be reported with the file's findings.
     */
    @Nonnull
    public List<Finding> apply(@Nonnull final List<Finding> findings, @Nonnull final FileContext context) {
        if (!config.getEngine().isSuppressions()) {
            return List.of();
        }
        final List<Finding> unused = new ArrayList<>();
        final var lines = context.getLines();
        for (int i = 0; i < lines.size(); i++) {
            final var line = lines.get(i);
            if (!line.contains(MARKER_TEXT)) {
                continue;
            }
            final var matcher = MARKER.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            final var markerLine = i + 1;
            final var nextLine = matcher.group(1) != null;
            final var target = nextLine ? markerLine + 1 : markerLine;
            final var reason = reason(matcher.group(2));
            var used = false;
            for (final var finding : findings) {
                if (finding.getLineNumber() == target) {
                    used = true;
                    if (!finding.isSuppressed()) {
                        finding.suppress(MARKER_TEXT, reason);
                    }
                }
            }
            if (!used) {
                final var marker = MARKER_TEXT + (nextLine ? "-next-line" : "");
                unused.add(unusedSuppression(context, markerLine, marker, reason == null ? marker
                    : marker + " "
                        + reason, "no finding on " + (nextLine ? "the next line" : "its line")
                            + "; remove the marker"));
            }
        }
        return unused;
    }

    /**
     * The reason after a marker without separators and comment closers, cut to {@value #MAX_REASON} characters, or
     * {@code null} if there is none.
     */
    static String reason(@Nonnull final String afterMarker) {
        final var withoutCloser = COMMENT_CLOSER.matcher(afterMarker).replaceFirst("");
        final var reason = SEPARATOR.matcher(withoutCloser).replaceFirst("").strip();
        if (reason.isEmpty()) {
            return null;
        }
        return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
    }

    /**
     * An {@value #UNUSED} warning for a marker or a {@code .facepalmignore} entry that suppresses nothing.
     *
     * @param shown The marker or entry as shown in the reports, unmasked: it must not contain a secret.
     * @param snippet The context snippet; as {@code shown}, it must not contain a secret.
     * @param why Why it is unused and what to do, recorded in the history.
     */
    @Nonnull
    static Finding unusedSuppression(@Nonnull final FileContext context,
                                     final int line,
                                     @Nonnull final String shown,
                                     @Nonnull final String snippet,
                                     @Nonnull final String why) {
        final var finding = Finding.builder()
            .patternName(UNUSED)
            .deduplicationHash("unused-suppression:" + context.getPath() + ":" + line)
            .secretValue(shown)
            .lineNumber(line)
            .context(context)
            .contextSnippet(snippet)
            .fixedSeverity(Severity.WARNING)
            .build();
        finding.note("Unused suppression: " + why);
        return finding;
    }
}
