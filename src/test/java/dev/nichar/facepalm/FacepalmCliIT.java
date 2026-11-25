/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the attached {@code cli} jar with {@code java -jar}, as a user without Maven would (#234). Its classpath is
 * only the jar, so a missing dependency, a lost Sisu index or a wrong {@code Main-Class} fails here.
 */
class FacepalmCliIT {

    @Test
    void clean_project_succeeds(@TempDir final Path output) throws Exception {
        final var result = run(fixture("clean_project"), output);

        assertThat(result.exitCode()).as(result.out()).isZero();
        assertThat(result.out())
            .contains("[INFO] Starting facepalm-maven-plugin ")
            .contains("[INFO] Discovered 2 files...")
            .contains("[INFO] SCAN RESULT : SUCCESS");
        assertThat(output.resolve("facepalm-findings.json")).exists();
    }

    @Test
    void dirty_project_warns_without_failing(@TempDir final Path output) throws Exception {
        final var result = run(fixture("dirty_project"), output);

        assertThat(result.exitCode()).as(result.out()).isZero();
        assertThat(result.out())
            .contains("[WARNING] [Generic Password Assignment] ")
            .contains("[INFO] SCAN RESULT : WARNINGS");
    }

    @Test
    void critical_finding_fails_with_exit_code_1(@TempDir final Path output) throws Exception {
        final var result = run(fixture("private_key_project"), output);

        assertThat(result.exitCode()).as(result.out()).isEqualTo(1);
        assertThat(result.out())
            .contains("[ERROR] [Private Key Block] ")
            .contains("[INFO] SCAN RESULT : FAILURE");
    }

    /**
     * Inline suppression and the unused-marker warning: code paths the other fixtures do not reach, so a class that
     * {@code minimizeJar} dropped wrongly fails here.
     */
    @Test
    void suppressed_finding_does_not_fail(@TempDir final Path output) throws Exception {
        final var result = run(fixture("suppressed_project"), output);

        assertThat(result.exitCode()).as(result.out()).isZero();
        assertThat(result.out())
            .contains("[WARNING] [Unused Suppression] ")
            .contains("1 findings suppressed by ");
    }

    /**
     * {@code --debug} logs at DEBUG level and lists processed and skipped files.
     */
    @Test
    void debug_lists_processed_files(@TempDir final Path output) throws Exception {
        final var result = run(fixture("dirty_project"), output, "--debug");

        assertThat(result.exitCode()).as(result.out()).isZero();
        assertThat(result.out()).contains("[DEBUG] ");
    }

    /**
     * Directory of an integration test fixture, as copied to the test classes.
     */
    private static Path fixture(final String name) throws URISyntaxException {
        final var url = FacepalmCliIT.class.getResource("/dev/nichar/facepalm/FacepalmMojoIT/" + name);
        return Path.of(Objects.requireNonNull(url, name).toURI());
    }

    /**
     * #235: picocli and the filtered version resource survive shading and {@code minimizeJar}.
     */
    @Test
    void version_and_usage_error_exit_codes() throws Exception {
        final var version = java("--version");
        assertThat(version.exitCode()).as(version.out()).isZero();
        assertThat(version.out().strip()).matches("facepalm \\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");

        final var usage = java("--no-such-option");
        assertThat(usage.exitCode()).as(usage.out()).isEqualTo(2);
        assertThat(usage.out()).contains("Unknown option: '--no-such-option'", "Usage: facepalm");
    }

    /**
     * Runs {@code java -jar <cli jar> [options] --output <output> <directory>} with the JRE running the tests.
     */
    private static Result run(final Path directory, final Path output, final String... options)
            throws IOException, InterruptedException {
        final var args = new ArrayList<>(List.of(options));
        args.addAll(List.of("--output", output.toString(), directory.toString()));
        return java(args.toArray(String[]::new));
    }

    /**
     * Runs {@code java -jar <cli jar> <args>} with the JRE running the tests.
     */
    private static Result java(final String... args) throws IOException, InterruptedException {
        final var jar = Path.of(Objects.requireNonNull(System.getProperty("facepalm.cli.jar"), "facepalm.cli.jar"));
        assertThat(jar).exists();
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Plain text: picocli detects ANSI support per platform.
        command.add("-Dpicocli.ansi=false");
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(args));
        final var process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        // Reads until the process closes its output, so the exit code is ready right after.
        final var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), out);
    }

    /**
     * Exit code and combined output of one CLI run.
     */
    private record Result(int exitCode, String out) {
    }
}
