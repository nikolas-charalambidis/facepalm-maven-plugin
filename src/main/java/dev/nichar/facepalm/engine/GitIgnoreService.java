/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

/**
 * Decides which files {@code .gitignore} ignores, to identify files with reduced public exposure.
 * <p>
 * Inside a Git repository, Git itself answers, so every rule of the {@code .gitignore} format applies: negation,
 * precedence by depth, {@code **}, escapes, and {@code .gitignore} files above the scan root (#9). Only
 * {@code .gitignore} files count: {@code .git/info/exclude} and the global {@code core.excludesFile} differ per
 * machine and would make scores differ too. Without Git or outside a repository, the {@code .gitignore} files below
 * the scan root are parsed as globs instead, without negation.
 */
@Named
@Singleton
public class GitIgnoreService {

    private final FacepalmLog log;

    /**
     * Longest time a {@code git ls-files} call may take before its result is treated as unknown.
     */
    private static final long GIT_TIMEOUT_SECONDS = 60;

    /**
     * Map of directory paths to their compiled glob matchers, the fallback without Git.
     */
    private final Map<Path, List<PathMatcher>> registry = new TreeMap<>();

    /**
     * Absolute, normalized paths of the files tracked by Git below the scan root, or {@code null} if unknown (Git is
     * not installed, the root is not in a repository, or {@code git ls-files} failed).
     */
    private volatile Set<Path> trackedFiles;

    /**
     * Untracked files and directories that Git ignores below the scan root, or {@code null} if unknown: then the
     * parsed {@link #registry} decides.
     */
    private volatile Ignored ignored;

    @Inject
    public GitIgnoreService(@Nullable final FacepalmLog log) {
        this.log = log != null ? log : FacepalmLog.console(false);
    }

    /**
     * Returns true if {@code .gitignore} ignores the file: per Git inside a repository, otherwise per the parsed
     * patterns.
     */
    public boolean isIgnored(@Nonnull final Path filePath) {
        final var gitIgnored = ignored;
        if (gitIgnored != null) {
            return gitIgnored.contains(filePath.toAbsolutePath().normalize());
        }
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
     * Asks Git once per scan which files below the scan root are tracked, and which untracked files {@code .gitignore}
     * ignores. Without Git or a repository, both stay unknown: {@link #isTracked} returns false, and
     * {@link #isIgnored} uses the parsed patterns.
     */
    public void loadGitState(@Nonnull final Path root) {
        final var normalized = root.toAbsolutePath().normalize();
        final var tracked = lsFiles(normalized);
        trackedFiles = tracked == null ? null : resolveAll(normalized, tracked);
        // --directory lists a wholly ignored directory once, as "dir/", instead of every file in it (target,
        // node_modules). --exclude-per-directory reads only .gitignore files, at every level of the repository.
        final var ignoredNames = tracked == null ? null
            : lsFiles(normalized, "--others", "--ignored", "--directory", "--exclude-per-directory=.gitignore");
        ignored = ignoredNames == null ? null : Ignored.of(normalized, ignoredNames);
    }

    /**
     * Resolves names relative to the root into absolute, normalized paths.
     */
    @Nonnull
    private static Set<Path> resolveAll(@Nonnull final Path root, @Nonnull final List<String> names) {
        final Set<Path> paths = new HashSet<>();
        for (final var name : names) {
            paths.add(root.resolve(name).normalize());
        }
        return Set.copyOf(paths);
    }

    /**
     * Runs {@code git ls-files -z} with the options in the root and returns the listed names, relative to the root,
     * or {@code null} if Git is not available, the root is not in a repository, or the command failed. With
     * {@code -z}, names are NUL-separated and never quoted.
     */
    @Nullable
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "A fixed git ls-files command line whose options are constants of this class, run without a shell.")
    private List<String> lsFiles(@Nonnull final Path root, @Nonnull final String... options) {
        Path output = null;
        try {
            output = Files.createTempFile("facepalm-ls-files", ".txt");
            final var command = new ArrayList<String>();
            // core.fsmonitor=false: the scanned repository's configuration must not make Git run a program.
            command.addAll(List.of("git", "-c", "core.fsmonitor=false", "ls-files", "-z"));
            command.addAll(List.of(options));
            final var process = new ProcessBuilder(command)
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
            final List<String> names = new ArrayList<>();
            for (final var name : new String(Files.readAllBytes(output), StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
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
                    if (glob.startsWith("/")) {
                        // Anchored patterns match only directly under the directory (#291).
                        glob = glob.substring(1);
                    } else {
                        // Relative patterns match at any depth, including next to the .gitignore: a "**/" prefix
                        // alone needs at least one directory level (#291).
                        glob = "{**/,}" + (glob.startsWith("**/") ? glob.substring(3) : glob);
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
     * Untracked files and directories that Git ignores, as absolute, normalized paths. A file is ignored if it is
     * listed itself, or if one of its parent directories is.
     */
    record Ignored(Set<Path> files, Set<Path> directories) {

        /**
         * Splits the names listed by {@code git ls-files --directory} into files and directories ({@code dir/}),
         * resolved against the root.
         */
        @Nonnull
        static Ignored of(@Nonnull final Path root, @Nonnull final List<String> names) {
            final List<String> files = new ArrayList<>();
            final List<String> directories = new ArrayList<>();
            for (final var name : names) {
                (name.endsWith("/") ? directories : files).add(name);
            }
            return new Ignored(resolveAll(root, files), resolveAll(root, directories));
        }

        /**
         * Returns true if the absolute, normalized file is ignored itself or lies in an ignored directory.
         */
        boolean contains(@Nonnull final Path file) {
            if (files.contains(file)) {
                return true;
            }
            for (var parent = file.getParent(); parent != null; parent = parent.getParent()) {
                if (directories.contains(parent)) {
                    return true;
                }
            }
            return false;
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
