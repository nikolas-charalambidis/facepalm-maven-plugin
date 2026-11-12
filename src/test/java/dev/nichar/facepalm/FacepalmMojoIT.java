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
            .anyMatch(line -> line.contains(
                "[Generic Password Assignment] Score: 69.8 (R:85/C:60) - "));
        assertThat(result).out().warn()
            .anyMatch(line -> line.contains(
                "facepalm-maven-plugin/target/maven-it/dev/nichar/facepalm/FacepalmMojoIT/dirty_project/project/src/main/resources/application.properties:2"));

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
            .anyMatch(line -> line.contains(
                "[Generic Password Assignment] Score: 69.8 (R:85/C:60) - "));
        assertThat(result).out().warn()
            .anyMatch(line -> line.contains(
                "facepalm-maven-plugin/target/maven-it/dev/nichar/facepalm/FacepalmMojoIT/dirty_project_fail_on_warnings/project/src/main/resources/application.properties:2"));

        assertThat(result).out().info()
            .contains("Discovered 2 files...")
            .contains("Starting scan of 2 files...")
            .contains("SCAN RESULT : WARNINGS")
            .contains("Warnings detected. Review recommended.")
            .contains("BUILD FAILURE");

        final var targetDirectory = result.getMavenProjectResult().getTargetProjectDirectory();
        assertThat(targetDirectory.resolve("target/facepalm-report.html")).doesNotExist();
        assertThat(targetDirectory.resolve("target/facepalm-report.sarif")).doesNotExist();
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
            .anyMatch(line -> line.contains("[Private Key Block] Score: 92.0 (R:100/C:60) - "));
        assertThat(result).out().error()
            .anyMatch(line -> line.contains(
                "facepalm-maven-plugin/target/maven-it/dev/nichar/facepalm/FacepalmMojoIT/private_key_project/project/src/main/resources/server.key:1"));
        // The copy in target/classes is excluded by the default skipDirs (issue #61).
        assertThat(result).out().error()
            .noneMatch(line -> line.contains("/target/classes/server.key"));

        assertThat(result).out().info()
            .contains("SCAN RESULT : FAILURE")
            .contains("BUILD FAILURE");
    }
}
