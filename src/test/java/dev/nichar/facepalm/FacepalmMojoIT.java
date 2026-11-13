/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.MavenVerbose;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Integration tests for the Facepalm Maven Plugin.
 * Manages isolated Maven builds to verify scanner behavior across various project scenarios.
 *
 * @author Nikolas Charalambidis
 */
@MavenJupiterExtension
class FacepalmMojoIT {

    /**
     * Verifies scanner success on a clean project baseline.
     */
    @MavenTest
    @MavenGoal("verify")
    // Use "facepalm:report" specifically instead of "site" or "report" because:
    // 1. "site" is a full lifecycle that is too slow for integration tests.
    // 2. "report" is the goal name defined in FacepalmReportMojo.
    // 3. Prefixing with "facepalm:" ensures we target our plugin's goal directly.
    @MavenGoal("facepalm:report")
    @MavenVerbose
    void clean_project(final MavenExecutionResult result) {
        // Verifies the build finished without errors and the scanner log matches expected success.
        assertThat(result).isSuccessful();

        assertThat(result).out().info()
            .contains("Discovered 2 files...")
            .contains("Starting scan of 2 files...")
            .contains("SCAN RESULT : SUCCESS")
            .contains("No secrets or sensitive patterns detected. Your secrets are safe.")
            .contains("BUILD SUCCESS");
    }

    /**
     * Verifies discoveries are logged without breaking the build by default.
     */
    @MavenTest
    @MavenGoal("verify")
    // Use "facepalm:report" specifically instead of "site" or "report" because:
    // 1. "site" is a full lifecycle that is too slow for integration tests.
    // 2. "report" is the goal name defined in FacepalmReportMojo.
    // 3. Prefixing with "facepalm:" ensures we target our plugin's goal directly.
    @MavenGoal("facepalm:report")
    @MavenVerbose
    void dirty_project(final MavenExecutionResult result) {
        // Verifies the build finished without errors and the scanner log matches expected success.
        assertThat(result).isSuccessful();

        assertThat(result).out().warn()
            .anyMatch(line -> line.startsWith("[Generic Password Assignment] ") && line.endsWith(
                " - Score: 69.8 (R:85/C:60)"));
        assertThat(result).out().warn()
            .anyMatch(line -> fixturePath(line).contains(
                "/FacepalmMojoIT/dirty_project/project/src/main/resources/application.properties:2"));

        assertThat(result).out().info()
            .contains("Discovered 2 files...")
            .contains("Starting scan of 2 files...")
            .contains("SCAN RESULT : WARNINGS")
            .contains("Warnings detected. Review recommended.")
            .contains("BUILD SUCCESS");
    }

    /**
     * Verifies that the plugin correctly terminates the build when thresholds are met.
     */
    @MavenTest
    @MavenGoal("verify")
    // Use "facepalm:report" specifically instead of "site" or "report" because:
    // 1. "site" is a full lifecycle that is too slow for integration tests.
    // 2. "report" is the goal name defined in FacepalmReportMojo.
    // 3. Prefixing with "facepalm:" ensures we target our plugin's goal directly.
    @MavenGoal("facepalm:report")
    @MavenVerbose
    void dirty_project_fail_on_warnings(final MavenExecutionResult result) {
        // Verifies the build finished with errors and the scanner log matches expected success.
        assertThat(result).isFailure();

        assertThat(result).out().warn()
            .anyMatch(line -> line.startsWith("[Generic Password Assignment] ") && line.endsWith(
                " - Score: 69.8 (R:85/C:60)"));
        assertThat(result).out().warn()
            .anyMatch(line -> fixturePath(line).contains(
                "/FacepalmMojoIT/dirty_project_fail_on_warnings/project/src/main/resources/application.properties:2"));

        assertThat(result).out().info()
            .contains("Discovered 2 files...")
            .contains("Starting scan of 2 files...")
            .contains("SCAN RESULT : WARNINGS")
            .contains("Warnings detected. Review recommended.")
            .contains("BUILD FAILURE");
    }

    /**
     * Verifies that the report goal finds the scan results with a non-default build directory and reporting output
     * directory (issue #71).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenGoal("facepalm:report")
    @MavenVerbose
    void custom_directories_project(final MavenExecutionResult result) throws IOException {
        assertThat(result).isSuccessful();
        assertThat(result).out().warn()
            .noneMatch(line -> line.contains("Facepalm results not found"));

        final var projectDirectory = result.getMavenProjectResult().getTargetProjectDirectory();
        assertThat(projectDirectory.resolve("build-output/facepalm-findings.json")).exists();
        final var report = projectDirectory.resolve("site-output/facepalm-report.html");
        assertThat(report).exists();
        assertThat(Files.readString(report))
            .contains("Generic Password Assignment")
            .doesNotContain("No scan data available");
    }

    /**
     * Verifies that a PKCS#8 private key (no key type in the PEM label) is detected and fails the build (issue #45).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void private_key_project(final MavenExecutionResult result) {
        assertThat(result).isFailure();

        assertThat(result).out().error()
            .anyMatch(line -> line.startsWith("[Private Key Block] ") && line.endsWith(" - Score: 92.0 (R:100/C:60)"));
        assertThat(result).out().error()
            .anyMatch(line -> fixturePath(line).contains(
                "/FacepalmMojoIT/private_key_project/project/src/main/resources/server.key:1"));
        // The copy in target/classes is excluded by the default skipDirs (issue #61).
        assertThat(result).out().error()
            .noneMatch(line -> line.contains("/target/classes/server.key"));

        assertThat(result).out().info()
            .contains("SCAN RESULT : FAILURE")
            .contains("BUILD FAILURE");
    }

    /**
     * Normalizes path separators of a log line, so path assertions are independent of the OS and of the folder the
     * repository is cloned into (issue #75).
     */
    private static String fixturePath(final String line) {
        return line.replace('\\', '/');
    }
}
