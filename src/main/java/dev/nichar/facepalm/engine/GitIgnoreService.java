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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
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
     * Longest time {@code git ls-files} may take before the tracked files are treated as unknown.
     */
    private static final long GIT_TIMEOUT_SECONDS = 60;

    /**
     * Map of directory paths to their compiled glob matchers.
     */
    private final Map<Path, List<PathMatcher>> registry = new TreeMap<>();

    /**
     * Absolute, normalized paths of the files tracked by Git below the scan root, or {@code null} if unknown (Git is
     * not installed, the root is not in a repository, or {@code git ls-files} failed).
     */
    private volatile Set<Path> trackedFiles;

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
     * Returns true if Git tracks the file. An ignored file that is tracked (force-added, or committed before it was
     * ignored) is public like any other committed file (#223). Returns false if the tracked files are unknown.
     */
    public boolean isTracked(@Nonnull final Path filePath) {
        final var tracked = trackedFiles;
        return tracked != null && tracked.contains(filePath.toAbsolutePath().normalize());
    }

    /**
     * Reads the files tracked by Git below the scan root once per scan, with {@code git ls-files}. Without Git or a
     * repository, the tracked files stay unknown and {@link #isTracked} returns false.
     */
    public void loadTrackedFiles(@Nonnull final Path root) {
        trackedFiles = readTrackedFiles(root.toAbsolutePath().normalize());
    }

    @Nullable
    private Set<Path> readTrackedFiles(@Nonnull final Path root) {
        Path output = null;
        try {
            output = Files.createTempFile("facepalm-ls-files", ".txt");
            // core.fsmonitor=false: the scanned repository's configuration must not make Git run a program.
            final var process = new ProcessBuilder("git", "-c", "core.fsmonitor=false", "ls-files", "-z")
                .directory(root.toFile())
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("git ls-files timed out in " + root + "; ignored files get the low-exposure discount.");
                return null;
            }
            if (process.exitValue() != 0) {
                log.debug("Not a Git repository: " + root + "; ignored files get the low-exposure discount.");
                return null;
            }
            final Set<Path> tracked = new HashSet<>();
            for (final var name : new String(Files.readAllBytes(output), StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    tracked.add(root.resolve(name).normalize());
                }
            }
            return Set.copyOf(tracked);
        } catch (IOException e) {
            log.debug("Git is not available (" + e.getMessage() + "); ignored files get the low-exposure discount.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException e) {
                    log.debug("Could not delete " + output + ": " + e.getMessage());
                }
            }
        }
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
