/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

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
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

/**
 * Manages {@code .gitignore} files to provide context-aware path filtering.
 * Parses project-level ignore rules to identify files with reduced public exposure.
 */
@Named
@Singleton
public class GitIgnoreService {

    private final Log log;

    /**
     * Map of directory paths to their compiled glob matchers.
     */
    private final Map<Path, List<PathMatcher>> registry = new TreeMap<>();

    @Inject
    public GitIgnoreService(@Nullable final Log log) {
        this.log = log != null ? log : new SystemStreamLog();
    }

    /**
     * Returns true if the specified path matches any registered ignore patterns.
     */
    public boolean isIgnored(@Nonnull final Path filePath) {
        return registry.entrySet().stream()
            .filter(entry -> filePath.startsWith(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream())
            .anyMatch(matcher -> matcher.matches(filePath));
    }

    /**
     * Parses a {@code .gitignore} file into {@link PathMatcher}s for its directory. The scanner calls this for every
     * {@code .gitignore} it finds while walking the project, so the tree is walked only once.
     */
    public void load(@Nonnull final Path ignoreFile) {
        final var directory = ignoreFile.getParent();
        final List<PathMatcher> matchers = new ArrayList<>();

        try {
            // Decode leniently: a .gitignore in a legacy encoding must not abort the scan.
            final var content = new String(Files.readAllBytes(ignoreFile), StandardCharsets.UTF_8);
            content.lines()
                .map(String::trim)
                // Skip empty lines and comments per Git specification.
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(pattern -> {
                    var glob = pattern;
                    if (pattern.endsWith("/")) {
                        // Convert directory-only matches to recursive globs.
                        glob += "**";
                    }
                    if (!pattern.startsWith("**/") && !pattern.startsWith("/")) {
                        // Ensure relative patterns match at any depth within the directory.
                        glob = "**/" + glob;
                    }
                    // Construct OS-agnostic glob strings.
                    // See: https://en.wikipedia.org/wiki/Glob_(programming)
                    final var fullGlob = "glob:" + escapeGlob(directory.toString().replace("\\", "/")) + "/" + glob;
                    try {
                        matchers.add(FileSystems.getDefault().getPathMatcher(fullGlob));
                    } catch (IllegalArgumentException e) {
                        // PatternSyntaxException for lines like "foo[" or "a{b": skip the line, keep the rest.
                        log.warn("Ignoring unsupported pattern '" + pattern + "' in " + ignoreFile + ": "
                            + e.getMessage());
                    }
                });
            registry.put(directory, matchers);
        } catch (IOException e) {
            log.warn("Could not read " + ignoreFile + ": " + e.getMessage());
        }
    }

    /**
     * Escapes glob metacharacters in a literal path, so a project directory like {@code my[app]} or {@code {x}}
     * is matched literally.
     */
    @Nonnull
    static String escapeGlob(@Nonnull final String literal) {
        final var escaped = new StringBuilder(literal.length());
        for (final char c : literal.toCharArray()) {
            if ("\\*?[]{}".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
