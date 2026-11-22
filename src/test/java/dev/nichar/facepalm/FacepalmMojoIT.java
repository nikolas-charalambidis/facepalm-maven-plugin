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
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Integration tests for the Facepalm Maven Plugin.
 * Manages isolated Maven builds to verify scanner behavior across various project scenarios.
 * <p>
 * Check the build result with {@code isSuccessful()} / {@code isFailure()}, not with the "BUILD FAILURE" log line.
 * Maven 4 logs that line at ERROR level instead of INFO (#365).
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
            .contains("Warnings detected. Review recommended. The build passes: failOnWarnings is false "
                + "(0 critical, 1 warnings).")
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
            // The result line follows the build result, not only the finding counts (#289).
            .contains("SCAN RESULT : FAILURE")
            .contains("Warnings detected. Review recommended. The build fails: failOnWarnings is true "
                + "(0 critical, 1 warnings).");
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
            .noneMatch(line -> line.contains("Facepalm results not found"))
            // skipDirs without useDefaultSkipDirs replaces the defaults, as in 1.0.x, with a warning (#258).
            .anyMatch(line -> line.contains("skipDirs replaces the default skip directories. Set useDefaultSkipDirs "
                + "explicitly"));

        final var projectDirectory = result.getMavenProjectResult().getTargetProjectDirectory();
        assertThat(projectDirectory.resolve("build-output/facepalm-findings.json")).exists();
        // A standalone report goal writes to ${project.build.directory}/reports (maven-reporting-impl 4).
        final var report = projectDirectory.resolve("build-output/reports/facepalm-report.html");
        assertThat(report).exists();
        assertThat(Files.readString(report))
            .contains("Generic Password Assignment")
            .doesNotContain("No scan data available");
    }

    /**
     * Verifies that {@code useDefaultSkipDirs=true} adds {@code skipDirs} to the defaults: both {@code node_modules}
     * (a default) and {@code generated} (configured) are skipped, so their secrets do not fail the build (#258).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void default_and_extra_skip_dirs_project(final MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        assertThat(result).out().info()
            .contains("Discovered 2 files...")
            .contains("SCAN RESULT : SUCCESS");
        assertThat(result).out().warn()
            .noneMatch(line -> line.contains("skipDirs replaces the default skip directories"));
    }

    /**
     * Verifies that a password and a token in a Maven {@code settings.xml}-style file are detected and fail the build,
     * while a {@code ${env...}} placeholder is not reported (issue #287).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void xml_credentials_project(final MavenExecutionResult result) {
        assertThat(result).isFailure();

        final var settings = "/FacepalmMojoIT/xml_credentials_project/project/src/main/resources/settings.xml:";
        assertThat(result).out().error()
            .anyMatch(line -> line.startsWith("[Generic Password Assignment] ")
                && fixturePath(line).contains(settings + "7 "));
        assertThat(result).out().error()
            .anyMatch(line -> line.startsWith("[Generic API Key Assignment] ")
                && fixturePath(line).contains(settings + "19 "));
        assertThat(result).out().plain()
            .noneMatch(line -> line.contains(settings + "12 "));
        // Maven-encrypted values (Maven 3 and 4) are reported as warnings, not errors (#296).
        for (final var line : List.of("20 ", "21 ")) {
            assertThat(result).out().warn()
                .anyMatch(warning -> fixturePath(warning).contains(settings + line));
            assertThat(result).out().error()
                .noneMatch(error -> fixturePath(error).contains(settings + line));
        }

        assertThat(result).out().info()
            .contains("SCAN RESULT : FAILURE");
    }

    /**
     * Verifies that {@code -Dfacepalm.skip=true} skips the scan, so a private key that would fail the build passes
     * (issue #38).
     */
    @MavenTest
    @MavenGoal("verify")
    @SystemProperty(value = "facepalm.skip", content = "true")
    void skipped_project(final MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        assertThat(result).out().info()
            .contains("Skipping Facepalm scan (facepalm.skip=true).")
            .noneMatch(line -> line.startsWith("SCAN RESULT"));
    }

    /**
     * Verifies inline suppression (#12): a {@code facepalm:ignore-next-line} marker above a private key suppresses it,
     * so the build passes, and a marker on a line without a finding is reported as an unused-suppression warning.
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void suppressed_project(final MavenExecutionResult result) throws IOException {
        assertThat(result).isSuccessful();

        assertThat(result).out().error().noneMatch(line -> line.startsWith("[Private Key Block] "));
        assertThat(result).out().warn()
            .anyMatch(line -> line.startsWith("[Unused Suppression] ") && fixturePath(line).contains(
                "/FacepalmMojoIT/suppressed_project/project/src/main/resources/application.properties:1"));
        assertThat(result).out().info()
            .contains(
                "1 findings suppressed by facepalm:ignore markers or .facepalmignore (finalSeverity SUPPRESSED in "
                    + "facepalm-findings.json).")
            .contains("SCAN RESULT : WARNINGS");

        final var findings = Files.readString(result.getMavenProjectResult().getTargetProjectDirectory()
            .resolve("target/facepalm-findings.json"));
        assertThat(findings).contains("\"finalSeverity\" : \"SUPPRESSED\"");
    }

    /**
     * Verifies the {@code .facepalmignore} file (#315): a {@code path:line} entry suppresses a private key, so the
     * build passes, and an entry that matches nothing is reported as an unused-suppression warning at its line.
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void suppression_file_project(final MavenExecutionResult result) throws IOException {
        assertThat(result).isSuccessful();

        assertThat(result).out().error().noneMatch(line -> line.startsWith("[Private Key Block] "));
        assertThat(result).out().warn()
            .anyMatch(line -> line.startsWith("[Unused Suppression] ") && fixturePath(line).contains(
                "/FacepalmMojoIT/suppression_file_project/project/.facepalmignore:3"));
        assertThat(result).out().info()
            .contains("1 findings suppressed by facepalm:ignore markers or .facepalmignore (finalSeverity SUPPRESSED "
                + "in facepalm-findings.json).")
            .contains("SCAN RESULT : WARNINGS");

        final var findings = Files.readString(result.getMavenProjectResult().getTargetProjectDirectory()
            .resolve("target/facepalm-findings.json"));
        assertThat(findings).contains("\"finalSeverity\" : \"SUPPRESSED\"");
    }

    /**
     * Verifies that a PKCS#8 private key (no key type in the PEM label) is detected and fails the build (issue #45).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void private_key_project(final MavenExecutionResult result) {
        assertThat(result).isFailure();

        // Full confidence: "FAKEKEYFORTESTS" in the key body is not the whole word "fake", so no mock context (#225).
        assertThat(result).out().error()
            .anyMatch(line -> line.startsWith("[Private Key Block] ") && line.endsWith(
                " - Score: 100.0 (R:100/C:100)"));
        assertThat(result).out().error()
            .anyMatch(line -> fixturePath(line).contains(
                "/FacepalmMojoIT/private_key_project/project/src/main/resources/server.key:1"));
        // The copy in target/classes is excluded by the default skipDirs (issue #61).
        assertThat(result).out().error()
            .noneMatch(line -> line.contains("/target/classes/server.key"));

        assertThat(result).out().info()
            .contains("SCAN RESULT : FAILURE");
    }

    /**
     * Verifies that an Azure Storage account key in a connection string is detected once, as such, and fails the build
     * (issue #224).
     */
    @MavenTest
    @MavenGoal("verify")
    @MavenVerbose
    void azure_storage_project(final MavenExecutionResult result) {
        assertThat(result).isFailure();

        assertThat(result).out().error()
            .anyMatch(line -> line.startsWith("[Azure Storage Account Key] ") && fixturePath(line).contains(
                "/FacepalmMojoIT/azure_storage_project/project/src/main/resources/application.properties:2"));
        assertThat(result).out().error()
            .noneMatch(line -> line.startsWith("[Azure OpenAI Key] "));

        assertThat(result).out().info()
            .contains("SCAN RESULT : FAILURE");
    }

    /**
     * Normalizes path separators of a log line, so path assertions are independent of the OS and of the folder the
     * repository is cloned into (issue #75).
     */
    private static String fixturePath(final String line) {
        return line.replace('\\', '/');
    }
}
