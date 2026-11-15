/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code .gitignore} handling, tested end to end through {@link FacepalmRunner#run} with the real wiring: a finding in
 * an ignored file gets lower risk ({@code -40}, low public exposure). The tests do not depend on how and when
 * {@code .gitignore} files are discovered (e.g. a separate walk or the scanner's walk, issue #15).
 * <p>
 * Ignored files are nested one level below the {@code .gitignore}, because root-level matching is part of the
 * spec-compliance work in #9.
 */
class GitIgnoreServiceTest {

    /**
     * Risk reduction applied to findings in ignored files.
     */
    private static final int EXPOSURE_DISCOUNT = 40;

    /**
     * A line detected as a password; the same in every file, so only the exposure differs.
     */
    private static final String SECRET_LINE = "db.password=Sup3rS3cretValue1\n";

    @Test
    void finding_in_ignored_file_has_lower_risk(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "secrets.properties\n");

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "conf/secrets.properties"))
            .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
    }

    @Test
    void nested_gitignore_applies_only_below_its_directory(@TempDir final Path tempDir) throws Exception {
        final var root = tempDir.resolve("project");
        Files.createDirectories(root.resolve("a/conf"));
        Files.createDirectories(root.resolve("b/conf"));
        Files.writeString(root.resolve("a/.gitignore"), "secrets.properties\n");
        Files.writeString(root.resolve("a/conf/secrets.properties"), SECRET_LINE);
        Files.writeString(root.resolve("b/conf/secrets.properties"), SECRET_LINE);

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "a/conf/secrets.properties"))
            .isEqualTo(risk(findings, "b/conf/secrets.properties") - EXPOSURE_DISCOUNT);
    }

    @Test
    void invalid_glob_lines_are_skipped_and_valid_lines_still_apply(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "foo[\na{b\nsecrets.properties\n");

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "conf/secrets.properties"))
            .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
    }

    @Test
    void non_utf8_gitignore_is_read(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "");
        Files.write(root.resolve(".gitignore"),
            "# Größe\nsecrets.properties\n".getBytes(StandardCharsets.ISO_8859_1));

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "conf/secrets.properties"))
            .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
    }

    @Test
    void project_directory_with_glob_metacharacters_is_matched_literally(@TempDir final Path tempDir)
            throws Exception {
        final var root = project(tempDir.resolve("my[app]{x}"), "secrets.properties\n");

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "conf/secrets.properties"))
            .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
    }

    /**
     * An unreadable directory must not stop other {@code .gitignore} files from being loaded (issue #64).
     */
    @Test
    void unreadable_directory_does_not_stop_gitignore_loading(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "secrets.properties\n");
        final var locked = root.resolve("pgdata");
        Files.createDirectories(locked);
        Assumptions.assumeTrue(locked.toFile().setReadable(false, false), "Cannot change permissions");
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "Running with privileges that ignore permissions");

            final var findings = scan(root, tempDir);

            assertThat(risk(findings, "conf/secrets.properties"))
                .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    /**
     * {@code .gitignore} files are regular project files: they are discovered and scanned like any other file.
     */
    @Test
    void gitignore_files_are_discovered_and_scanned(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "secrets.properties\n");
        final var container = TestContainer.create(config());

        container.getInstance(FacepalmRunner.class).run(root, tempDir.resolve("out"), "test");

        final var stats = container.getInstance(ScannerEngine.class).getStats();
        assertThat(stats.getFilesDiscovered().sum()).isEqualTo(3);
        assertThat(stats.getFilesScanned().sum()).isEqualTo(3);
    }

    /**
     * Issue #223: a tracked file is public even if it matches {@code .gitignore} (force-added, or committed before it
     * was ignored), so it keeps its full risk. An ignored file that is not tracked still gets the discount.
     */
    @Test
    void tracked_ignored_file_keeps_full_risk(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir.resolve("project"), "secrets.properties\nlocal.properties\n");
        Files.writeString(root.resolve("conf/local.properties"), SECRET_LINE);
        Assumptions.assumeTrue(git(root, "init", "-q"), "git is not available");
        Assumptions.assumeTrue(git(root, "add", "-f", "conf/secrets.properties", "conf/app.properties"));

        final var findings = scan(root, tempDir);

        assertThat(risk(findings, "conf/secrets.properties")).isEqualTo(risk(findings, "conf/app.properties"));
        assertThat(risk(findings, "conf/local.properties"))
            .isEqualTo(risk(findings, "conf/app.properties") - EXPOSURE_DISCOUNT);
    }

    @Test
    void escape_glob_escapes_metacharacters() {
        assertThat(GitIgnoreService.escapeGlob("/a/my[app]/{x}/*?")).isEqualTo("/a/my\\[app\\]/\\{x\\}/\\*\\?");
    }

    /**
     * Project with {@code conf/secrets.properties} and {@code conf/app.properties} (same content) and a root
     * {@code .gitignore}.
     */
    private static Path project(final Path root, final String gitignore) throws Exception {
        Files.createDirectories(root.resolve("conf"));
        Files.writeString(root.resolve(".gitignore"), gitignore);
        Files.writeString(root.resolve("conf/secrets.properties"), SECRET_LINE);
        Files.writeString(root.resolve("conf/app.properties"), SECRET_LINE);
        return root;
    }

    /**
     * Runs a full scan and returns the findings written to {@code facepalm-findings.json}.
     */
    private static List<Map<String, Object>> scan(final Path root, final Path tempDir) throws Exception {
        final var output = tempDir.resolve("out");
        TestContainer.create(config()).getInstance(FacepalmRunner.class).run(root, output, "test");
        return new ObjectMapper().readValue(output.resolve("facepalm-findings.json").toFile(),
            new TypeReference<List<Map<String, Object>>>() {
            });
    }

    /**
     * Runs a git command in the directory and returns whether it succeeded; false if git is not installed.
     */
    private static boolean git(final Path directory, final String... args) throws InterruptedException {
        final var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            final var process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static FacepalmConfig config() {
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        config.getScoring().setFailOnError(false);
        return config;
    }

    private static int risk(final List<Map<String, Object>> findings, final String relativeFile) {
        final var suffix = "/" + relativeFile;
        final var matching = findings.stream()
            .filter(f -> f.get("fileAbsolutePath").toString().replace('\\', '/').endsWith(suffix))
            .toList();
        assertThat(matching).as("findings in %s", relativeFile).hasSize(1);
        return ((Number) matching.get(0).get("riskScore")).intValue();
    }
}
