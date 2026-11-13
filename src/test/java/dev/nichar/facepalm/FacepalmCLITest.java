/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Command-line options of {@link FacepalmCLI}.
 */
class FacepalmCLITest {

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
        assertThrows(MojoExecutionException.class, () -> FacepalmCLI.parse(new String[] {
            "--verbose" }));
        assertThrows(MojoExecutionException.class, () -> FacepalmCLI.parse(new String[] {
            "--output" }));
        assertThrows(MojoExecutionException.class, () -> FacepalmCLI.parse(new String[] {
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
