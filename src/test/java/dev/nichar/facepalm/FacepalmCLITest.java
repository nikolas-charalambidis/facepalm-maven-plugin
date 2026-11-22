/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nichar.facepalm.engine.ScanFailedException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command-line options of {@link FacepalmCLI}.
 */
class FacepalmCLITest {

    /**
     * Fixture projects of the integration tests, scanned here without Maven.
     */
    private static final Path FIXTURES = Paths.get("src/test/resources-its/dev/nichar/facepalm/FacepalmMojoIT");

    /**
     * #233: the CLI wires the engine with its console log, without the Maven or Plexus logging API.
     */
    @Test
    void scans_a_clean_directory_and_writes_the_reports(@TempDir final Path output) throws Exception {
        FacepalmCLI.main(new String[] {
            "--output", output.toString(), FIXTURES.resolve("clean_project").toString() });

        assertTrue(output.resolve("facepalm-findings.json").toFile().isFile());
    }

    @Test
    void fails_the_scan_of_a_dirty_directory(@TempDir final Path output) {
        final var failure = assertThrows(ScanFailedException.class, () -> FacepalmCLI.main(new String[] {
            "--output", output.toString(), FIXTURES.resolve("private_key_project").toString() }));

        assertTrue(failure.getMessage().startsWith("Facepalm scan failed: "), failure.getMessage());
    }

    @Test
    void execution_failure_states_the_cause() {
        final var cause = new IOException("disk full");

        final var failure = FacepalmCLI.executionFailure(cause);

        assertEquals("Facepalm scan failed: disk full", failure.getMessage());
        assertEquals(cause, failure.getCause());
        assertEquals("Facepalm scan failed: java.lang.NullPointerException",
            FacepalmCLI.executionFailure(new NullPointerException()).getMessage());
    }

    @Test
    void defaults_to_current_directory_its_target_and_info_level() throws Exception {
        final var options = FacepalmCLI.parse(new String[0]);

        final var cwd = Paths.get(".").toAbsolutePath().normalize();
        assertEquals(cwd, options.root());
        assertEquals(cwd.resolve("target"), options.output());
        assertFalse(options.debug());
    }

    @Test
    void output_defaults_to_target_of_the_scanned_directory() throws Exception {
        final var options = FacepalmCLI.parse(new String[] {
            "some/project" });

        assertEquals(Paths.get("some/project").toAbsolutePath().normalize().resolve("target"), options.output());
    }

    @Test
    void reads_debug_and_output_options() throws Exception {
        final var options = FacepalmCLI.parse(new String[] {
            "--debug", "--output", "reports", "project" });

        assertTrue(options.debug());
        assertEquals(Paths.get("reports").toAbsolutePath().normalize(), options.output());
        assertEquals(Paths.get("project").toAbsolutePath().normalize(), options.root());
    }

    @Test
    void rejects_unknown_option_missing_value_and_second_directory() {
        assertThrows(IllegalArgumentException.class, () -> FacepalmCLI.parse(new String[] {
            "--verbose" }));
        assertThrows(IllegalArgumentException.class, () -> FacepalmCLI.parse(new String[] {
            "--output" }));
        assertThrows(IllegalArgumentException.class, () -> FacepalmCLI.parse(new String[] {
            "a", "b" }));
    }

    /**
     * Unpackaged classes carry no Maven metadata; the packaged jar does (checked when releasing).
     */
    @Test
    void version_is_unknown_without_jar_metadata() {
        assertEquals("unknown", FacepalmCLI.version());
    }
}
