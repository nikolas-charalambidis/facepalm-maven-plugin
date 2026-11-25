/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code --staged} (#236): only the files staged for the next commit are scanned, with their staged content. Each
 * test builds a Git repository in a temporary directory. The fake key is assembled at runtime, so this file contains
 * no literal key that secret scanners would flag.
 */
class StagedScanTest {

    /**
     * A fake private key block, an ERROR finding in any file.
     */
    private static final String KEY = "-----BEGIN " + "PRIVATE KEY-----\n"
        + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\n" + "-----END " + "PRIVATE KEY-----\n";

    @TempDir
    private Path repo;

    @TempDir
    private Path output;

    @BeforeAll
    static void requireGit() {
        assumeTrue(gitAvailable(), "git is not on the PATH");
    }

    @BeforeEach
    void initRepository() throws Exception {
        git("init", "-q");
    }

    @Test
    void added_secret_fails() throws Exception {
        write("server.key", KEY);
        git("add", "server.key");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
    }

    @Test
    void modified_secret_fails() throws Exception {
        commit("server.key", "placeholder\n");
        write("server.key", KEY);
        git("add", "server.key");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
    }

    /**
     * A rename is staged as the addition of the new path, so the file is scanned under its new name.
     */
    @Test
    void renamed_secret_fails_under_its_new_name() throws Exception {
        commit("server.key", KEY);
        git("mv", "server.key", "moved.key");

        final var result = scan("--staged");

        assertThat(result.exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
        assertThat(findings()).contains("moved.key").doesNotContain("server.key");
    }

    @Test
    void deleted_secret_is_not_scanned() throws Exception {
        commit("server.key", KEY);
        git("rm", "-q", "server.key");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_OK);
    }

    /**
     * The staged content counts, not the working copy: a secret that was staged and then removed from the file is
     * still committed. A full scan of the working tree passes.
     */
    @Test
    void staged_secret_removed_from_the_working_tree_still_fails() throws Exception {
        write("server.key", KEY);
        git("add", "server.key");
        write("server.key", "placeholder\n");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
        assertThat(scan().exitCode()).isEqualTo(FacepalmCLI.EXIT_OK);
    }

    /**
     * A secret only in unstaged changes and untracked files is not part of the commit.
     */
    @Test
    void unstaged_secret_passes() throws Exception {
        commit("README.md", "readme\n");
        commit("tracked.key", "placeholder\n");
        write("README.md", "readme, staged\n");
        git("add", "README.md");
        write("tracked.key", KEY);
        write("untracked.key", KEY);

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_OK);
        assertThat(scan().exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
    }

    @Test
    void no_staged_files_pass() throws Exception {
        write("untracked.key", KEY);

        final var result = scan("--staged");

        assertThat(result.exitCode()).isEqualTo(FacepalmCLI.EXIT_OK);
    }

    @Test
    void path_with_spaces_is_scanned() throws Exception {
        write("my dir/server key.pem", KEY);
        git("add", "my dir/server key.pem");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_FINDINGS);
        assertThat(findings()).contains("server key.pem");
    }

    /**
     * {@code skipDirs} applies as in a full scan: {@code target} directly under the scan root is build output.
     */
    @Test
    void staged_file_in_a_skipped_directory_is_not_scanned() throws Exception {
        write("target/server.key", KEY);
        git("add", "-f", "target/server.key");

        assertThat(scan("--staged").exitCode()).isEqualTo(FacepalmCLI.EXIT_OK);
    }

    @Test
    void directory_outside_a_repository_exits_with_2(@TempDir final Path plain) {
        final var result = execute("--staged", "--output", output.toString(), plain.toString());

        assertThat(result.exitCode()).isEqualTo(FacepalmCLI.EXIT_ERROR);
        assertThat(result.err()).startsWith("Facepalm scan failed: ").contains("Git");
    }

    private Result scan(final String... options) {
        final var args = new ArrayList<>(List.of(options));
        args.addAll(List.of("--output", output.toString(), repo.toString()));
        return execute(args.toArray(String[]::new));
    }

    private String findings() throws IOException {
        return Files.readString(output.resolve("facepalm-findings.json"));
    }

    private void write(final String path, final String content) throws IOException {
        final var file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void commit(final String path, final String content) throws Exception {
        write(path, content);
        git("add", path);
        git("commit", "-q", "-m", "add " + path);
    }

    /**
     * Runs Git in the repository with a fixed identity and no signing, independent of the user's configuration.
     */
    private void git(final String... args) throws Exception {
        final var command = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c",
            "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-c",
            "init.defaultBranch=main"));
        command.addAll(List.of(args));
        final var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        final var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git %s: %s", String.join(" ", args), out).isZero();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Result execute(final String... args) {
        final var err = new StringWriter();
        final var commandLine = FacepalmCLI.commandLine();
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        commandLine.setErr(new PrintWriter(err, true));
        return new Result(commandLine.execute(args), err.toString());
    }

    /**
     * Exit code and the text picocli wrote to standard error.
     */
    private record Result(int exitCode, String err) {
    }
}
