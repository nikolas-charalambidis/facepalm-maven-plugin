/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The files staged for the next commit, below a directory, with their staged content (#236). A pre-commit hook scans
 * these instead of the working tree: a secret that was staged and then removed from the working copy is still
 * committed, and an unstaged change is not.
 * <p>
 * The list comes from {@code git diff --cached --raw}: added, copied, modified and renamed files, with the id of the
 * staged blob. Deleted files are not scanned, nor are submodules (gitlinks). Renames are listed as additions of the
 * new path.
 */
public final class StagedFiles {

    /**
     * Longest time one Git command may take.
     */
    private static final long GIT_TIMEOUT_SECONDS = 60;

    /**
     * Mode of a submodule entry (gitlink): it points to a commit of another repository, not to content.
     */
    private static final String GITLINK_MODE = "160000";

    /**
     * Number of fields in the {@code --raw} header of one entry: {@code :oldmode newmode oldblob newblob status}.
     */
    private static final int RAW_HEADER_FIELDS = 5;

    /**
     * Directory the files are listed for; Git commands run in it.
     */
    private final Path root;

    /**
     * Git executable, {@code git} unless a test replaces it.
     */
    private final String git;

    /**
     * Staged blob id of each file, by its absolute path, in Git's order.
     */
    private final Map<Path, String> blobs;

    private StagedFiles(final Path root, final String git, final Map<Path, String> blobs) {
        this.root = root;
        this.git = git;
        this.blobs = blobs;
    }

    /**
     * Lists the files staged below the directory.
     *
     * @throws IOException if Git is not on the {@code PATH}, the directory is not in a Git work tree, or Git fails
     */
    @Nonnull
    public static StagedFiles of(@Nonnull final Path root) throws IOException, InterruptedException {
        return of(root, "git");
    }

    @Nonnull
    static StagedFiles of(@Nonnull final Path root, @Nonnull final String git)
            throws IOException, InterruptedException {
        final var inWorkTree = new String(run(root, git, "rev-parse", "--is-inside-work-tree"),
            StandardCharsets.UTF_8).strip();
        if (!"true".equals(inWorkTree)) {
            throw new IOException("Not a Git work tree: " + root + ". --staged needs a Git repository.");
        }
        // --relative: paths below and relative to the scanned directory. -z: NUL-separated and never quoted.
        final var raw = new String(run(root, git, "diff", "--cached", "--raw", "-z", "--no-renames",
            "--diff-filter=ACMR", "--relative", "--no-abbrev"), StandardCharsets.UTF_8);
        return new StagedFiles(root, git, parse(root, raw));
    }

    /**
     * Parses {@code git diff --raw -z} output: per file, a header {@code :oldmode newmode oldblob newblob status}
     * and the path, each followed by NUL (with {@code --no-renames} there is one path per entry).
     */
    @Nonnull
    static Map<Path, String> parse(@Nonnull final Path root, @Nonnull final String raw) {
        final Map<Path, String> blobs = new LinkedHashMap<>();
        final var fields = raw.split("\0");
        for (int i = 0; i + 1 < fields.length; i += 2) {
            final var header = fields[i].substring(1).split(" ");
            if (header.length == RAW_HEADER_FIELDS && !GITLINK_MODE.equals(header[1])) {
                blobs.put(root.resolve(fields[i + 1]).normalize(), header[3]);
            }
        }
        return blobs;
    }

    /**
     * The staged files, as absolute paths below the directory.
     */
    @Nonnull
    public List<Path> paths() {
        return Collections.unmodifiableList(new ArrayList<>(blobs.keySet()));
    }

    /**
     * Size of the staged content of the file.
     */
    public long size(@Nonnull final Path file) throws IOException {
        final var size = new String(git("cat-file", "-s", blob(file)), StandardCharsets.UTF_8).strip();
        try {
            return Long.parseLong(size);
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected size of " + file + ": " + size, e);
        }
    }

    /**
     * Staged content of the file.
     */
    @Nonnull
    public byte[] read(@Nonnull final Path file) throws IOException {
        return git("cat-file", "blob", blob(file));
    }

    @Nonnull
    private String blob(@Nonnull final Path file) throws IOException {
        final var blob = blobs.get(file);
        if (blob == null) {
            throw new IOException("Not staged: " + file);
        }
        return blob;
    }

    @Nonnull
    private byte[] git(@Nonnull final String... args) throws IOException {
        try {
            return run(root, git, args);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading the staged content", e);
        }
    }

    /**
     * Runs a Git command in the directory and returns its standard output. The output goes to a temporary file, so a
     * large blob cannot block the process on a full pipe.
     *
     * @throws IOException if Git is not on the {@code PATH}, or the command fails or times out
     */
    @Nonnull
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "Fixed git subcommands; the only variable arguments are blob ids from git itself, run without a shell.")
    private static byte[] run(@Nonnull final Path root, @Nonnull final String git, @Nonnull final String... args)
            throws IOException, InterruptedException {
        final var output = Files.createTempFile("facepalm-git", ".out");
        try {
            final var command = new ArrayList<String>();
            // core.fsmonitor=false: the scanned repository's configuration must not make Git run a program.
            command.addAll(List.of(git, "-c", "core.fsmonitor=false"));
            command.addAll(List.of(args));
            final Process process;
            try {
                process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            } catch (IOException e) {
                throw new IOException("--staged needs git on the PATH: " + e.getMessage(), e);
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + args[0] + " timed out in " + root);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Not a Git work tree, or git " + args[0] + " failed in " + root);
            }
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
