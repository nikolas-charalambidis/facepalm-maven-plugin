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
     * Directory of an integration test fixture, as copied to the test classes.
     */
    private static Path fixture(final String name) throws URISyntaxException {
        final var url = FacepalmCliIT.class.getResource("/dev/nichar/facepalm/FacepalmMojoIT/" + name);
        return Path.of(Objects.requireNonNull(url, name).toURI());
    }

    /**
     * Runs {@code java -jar <cli jar> --output <output> <directory>} with the JRE running the tests.
     */
    private static Result run(final Path directory, final Path output) throws IOException, InterruptedException {
        final var jar = Path.of(Objects.requireNonNull(System.getProperty("facepalm.cli.jar"), "facepalm.cli.jar"));
        assertThat(jar).exists();
        final var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var process = new ProcessBuilder(List.of(java, "-jar", jar.toString(), "--output", output.toString(),
            directory.toString()))
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
