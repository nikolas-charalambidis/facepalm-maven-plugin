/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.FacepalmConfig;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * The {@code .facepalmignore} file at the scan root (#315): suppressions reviewed in one place, for files that cannot
 * take an inline {@code facepalm:ignore} marker (#12), such as plain JSON. One entry per line, each with an optional
 * reason after {@code " #"}:
 * <ul>
 * <li>{@code path:line} suppresses the findings on one line of one file;</li>
 * <li>{@code path} or a glob ({@code src/test/fixtures/**}) suppresses every finding in the matching files;</li>
 * <li>{@code value:<token>} suppresses a finding whose value is exactly {@code <token>}, in any file: a token that is
 * safe to keep in the repository, such as a public test key.</li>
 * </ul>
 * Paths are relative to the scan root, with {@code /} as the separator. Lines starting with {@code #} are comments.
 * Matching findings are reported as {@link Severity#SUPPRESSED}. An entry that matches no finding in the whole scan is
 * reported as an {@value InlineSuppressor#UNUSED} warning at its line in the file, so stale entries are removed. The
 * file itself is not scanned: its value entries would otherwise be found in it and always count as used.
 */
@Named
@Singleton
public class SuppressionFile {

    /**
     * Name of the file at the scan root.
     */
    public static final String FILE_NAME = ".facepalmignore";

    /**
     * Prefix of an entry that lists a safe value instead of a path.
     */
    private static final String VALUE_PREFIX = "value:";

    /**
     * Entry and its optional reason: the reason starts at the first {@code #} after whitespace, so a value or a path
     * may contain {@code #}.
     */
    private static final Pattern ENTRY = Pattern.compile("^(.*?)(?:\\s+#\\s*(.*))?$");

    /**
     * Path with a line number: {@code path:12}.
     */
    private static final Pattern PATH_LINE = Pattern.compile("^(.+):(\\d+)$");

    private final FacepalmLog log;

    @Inject
    private FacepalmConfig config;

    /**
     * Absolute, normalized path of the loaded file, or {@code null} if there is none.
     */
    private volatile Path file;

    /**
     * Context of the loaded file, for the location of unused-entry warnings.
     */
    private volatile FileContext fileContext;

    /**
     * Entries of the loaded file.
     */
    private volatile List<Entry> entries = List.of();

    @Inject
    public SuppressionFile(@Nullable final FacepalmLog log) {
        this.log = log != null ? log : FacepalmLog.console(false);
    }

    /**
     * Reads {@value #FILE_NAME} at the scan root once per scan. Without the file, or with {@code suppressions} off,
     * nothing is suppressed.
     */
    public void load(@Nonnull final Path root) {
        file = null;
        fileContext = null;
        entries = List.of();
        final var normalizedRoot = root.toAbsolutePath().normalize();
        final var candidate = normalizedRoot.resolve(FILE_NAME);
        if (!config.getEngine().isSuppressions() || !Files.isRegularFile(candidate)) {
            return;
        }
        try {
            // Decode leniently, as .gitignore: a legacy encoding must not abort the scan.
            final var content = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            final var lines = content.lines().toList();
            final List<Entry> parsed = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                final var entry = parse(lines.get(i), i + 1);
                if (entry != null) {
                    parsed.add(entry);
                }
            }
            file = candidate;
            fileContext = new FileContext(candidate, normalizedRoot.relativize(candidate), content, lines);
            entries = List.copyOf(parsed);
        } catch (IOException e) {
            log.warn("Could not read " + candidate + ": " + e.getMessage());
        }
    }

    /**
     * Whether the path is the loaded {@value #FILE_NAME}, which is not scanned.
     */
    public boolean isSuppressionFile(@Nonnull final Path path) {
        final var loaded = file;
        return loaded != null && loaded.equals(path.toAbsolutePath().normalize());
    }

    /**
     * Suppresses the file's findings that an entry matches. Thread-safe: files are scanned in parallel.
     */
    public void apply(@Nonnull final List<Finding> findings, @Nonnull final FileContext context) {
        final var current = entries;
        if (current.isEmpty() || findings.isEmpty()) {
            return;
        }
        final var relative = context.getRelativePath();
        for (final var entry : current) {
            for (final var finding : findings) {
                if (entry.matches(relative, finding)) {
                    entry.used().set(true);
                    if (!finding.isSuppressed()) {
                        finding.suppress(FILE_NAME + ":" + entry.line(), entry.reason());
                    }
                }
            }
        }
    }

    /**
     * {@value InlineSuppressor#UNUSED} warnings for the entries that matched no finding, located in the file. Call once
     * after all files are scanned.
     */
    @Nonnull
    public List<Finding> unusedEntries() {
        final var context = fileContext;
        if (context == null) {
            return List.of();
        }
        final List<Finding> unused = new ArrayList<>();
        for (final var entry : entries) {
            if (!entry.used().get()) {
                final var shown = entry.shown();
                unused.add(InlineSuppressor.unusedSuppression(context, entry.line(), shown, entry.reason() == null
                    ? shown
                    : shown + " # " + entry.reason(), "no finding matches this " + FILE_NAME
                        + " entry; remove it"));
            }
        }
        return unused;
    }

    /**
     * Parses one line of the file, or returns {@code null} for a comment, a blank line or an invalid entry.
     */
    @Nullable
    private Entry parse(@Nonnull final String rawLine, final int lineNumber) {
        final var line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        final var entry = ENTRY.matcher(line);
        if (!entry.matches()) {
            return null;
        }
        final var body = entry.group(1).strip();
        final var reason = entry.group(2) == null || entry.group(2).isBlank() ? null : entry.group(2).strip();
        if (body.startsWith(VALUE_PREFIX)) {
            final var value = body.substring(VALUE_PREFIX.length()).strip();
            if (value.isEmpty()) {
                log.warn("Ignoring empty value entry in " + FILE_NAME + ":" + lineNumber);
                return null;
            }
            return new Entry(lineNumber, null, 0, value, reason, VALUE_PREFIX + Finding.mask(value),
                new AtomicBoolean());
        }
        var path = body.replace('\\', '/');
        var targetLine = 0;
        final var pathLine = PATH_LINE.matcher(path);
        if (pathLine.matches()) {
            try {
                targetLine = Integer.parseInt(pathLine.group(2));
            } catch (NumberFormatException e) {
                // A line number beyond int range: an invalid entry must not abort the scan.
                log.warn("Ignoring entry '" + body + "' in " + FILE_NAME + ":" + lineNumber + ": invalid line number");
                return null;
            }
            path = pathLine.group(1);
        }
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.substring(path.startsWith("./") ? 2 : 1);
        }
        try {
            final var matcher = FileSystems.getDefault().getPathMatcher("glob:" + path);
            return new Entry(lineNumber, matcher, targetLine, null, reason, body, new AtomicBoolean());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid entry '" + body + "' in " + FILE_NAME + ":" + lineNumber + ": "
                + e.getMessage());
            return null;
        }
    }

    /**
     * One entry: a path or glob, optionally with a line ({@code targetLine} {@code 0} means the whole file), or a safe
     * value.
     *
     * @param shown The entry as reported when unused, with a value masked.
     */
    private record Entry(int line, PathMatcher path, int targetLine, String value, String reason, String shown,
            AtomicBoolean used) {

        boolean matches(@Nonnull final Path relative, @Nonnull final Finding finding) {
            if (value != null) {
                return value.equals(finding.getSecretValue());
            }
            return path.matches(relative) && (targetLine == 0 || targetLine == finding.getLineNumber());
        }
    }
}
