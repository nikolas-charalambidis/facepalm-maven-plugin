/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #315: the {@code .facepalmignore} file at the scan root, tested end to end through {@link FacepalmRunner#run}:
 * the build decision and {@code facepalm-findings.json}. Key IDs are generated at runtime, so this file contains no
 * literal token that secret scanners would flag.
 */
class SuppressionFileTest {

    /**
     * Two different AWS access key IDs: ERROR findings, which fail the build by default.
     */
    private static final Random RANDOM = new Random(315);

    /**
     * First key.
     */
    private static final String KEY_1 = "AKIA" + random(16);

    /**
     * Second key.
     */
    private static final String KEY_2 = "AKIA" + random(16);

    /**
     * JSON file with one key per line, at {@code src/main/resources/keys.json}.
     */
    private static final String KEYS_JSON = "{\n\"first\": \"" + KEY_1 + "\",\n\"second\": \"" + KEY_2 + "\"\n}\n";

    @Test
    void path_and_line_suppresses_one_line(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir, "src/main/resources/keys.json:2  # vendor sample\n");

        assertThatThrownBy(() -> run(root, tempDir, config())).isInstanceOf(MojoFailureException.class);

        assertThat(read(tempDir)).extracting(f -> f.get("lineNumber"), f -> f.get("finalSeverity"))
            .containsExactlyInAnyOrder(tuple(2, "SUPPRESSED"), tuple(3, "ERROR"));
        final var suppressed = scan(root).stream().filter(Finding::isSuppressed).findFirst().orElseThrow();
        assertThat(suppressed.getSuppressionReason()).isEqualTo("vendor sample");
        assertThat(suppressed.getScoreHistory()).contains("Suppressed by .facepalmignore:1: vendor sample");
    }

    @Test
    void path_suppresses_the_whole_file_and_only_that_file(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir, "./src/main/resources/keys.json\n");
        Files.writeString(root.resolve("src/main/resources/other.properties"), "aws.key=AKIA" + random(16) + "\n");

        assertThatThrownBy(() -> run(root, tempDir, config())).isInstanceOf(MojoFailureException.class);

        assertThat(read(tempDir)).extracting(f -> fileName(f), f -> f.get("finalSeverity"))
            .containsExactlyInAnyOrder(
                tuple("keys.json", "SUPPRESSED"),
                tuple("keys.json", "SUPPRESSED"),
                tuple("other.properties", "ERROR"));
    }

    @Test
    void glob_suppresses_every_matching_file(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir, "# generated test data\n\nsrc/**/*.json\n");

        run(root, tempDir, config());

        assertThat(read(tempDir)).extracting(f -> f.get("finalSeverity")).containsOnly("SUPPRESSED").hasSize(2);
    }

    @Test
    void value_suppresses_that_value_in_any_file(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir, "value:" + KEY_1 + " # public test key of the vendor\n");
        Files.writeString(root.resolve("src/main/resources/other.properties"), "aws.key=" + KEY_1 + "\n");

        assertThatThrownBy(() -> run(root, tempDir, config())).isInstanceOf(MojoFailureException.class);

        assertThat(read(tempDir)).extracting(f -> fileName(f), f -> f.get("finalSeverity"))
            .containsExactlyInAnyOrder(
                tuple("keys.json", "SUPPRESSED"),
                tuple("keys.json", "ERROR"),
                tuple("other.properties", "SUPPRESSED"));
    }

    /**
     * Entries that match nothing are warnings at their line in {@code .facepalmignore}, with a value masked. The file
     * itself is not scanned, so its value entry does not match itself.
     */
    @Test
    void unused_entries_are_warnings_at_their_line(@TempDir final Path tempDir) throws Exception {
        final var unknownKey = "AKIA" + random(16);
        final var root = project(tempDir, "src/main/resources/keys.json:1\nsrc/test/**\nvalue:" + unknownKey
            + "\nsrc/main/resources/keys.json\n");

        run(root, tempDir, config());

        final var warnings = read(tempDir).stream().filter(f -> "WARNING".equals(f.get("finalSeverity"))).toList();
        assertThat(warnings).extracting(f -> f.get("patternName"), f -> fileName(f), f -> f.get("lineNumber"),
            f -> f.get("maskedSecret"))
            .containsExactlyInAnyOrder(
                tuple(InlineSuppressor.UNUSED, ".facepalmignore", 1, "src/main/resources/keys.json:1"),
                tuple(InlineSuppressor.UNUSED, ".facepalmignore", 2, "src/test/**"),
                tuple(InlineSuppressor.UNUSED, ".facepalmignore", 3, "value:" + Finding.mask(unknownKey)));
        assertThat(read(tempDir).toString()).doesNotContain(unknownKey);
    }

    @Test
    void audit_switch_ignores_the_file(@TempDir final Path tempDir) {
        final var root = project(tempDir, "src/main/resources/keys.json\nsrc/test/**\n");
        final var audit = config();
        audit.getEngine().setSuppressions(false);

        assertThatThrownBy(() -> run(root, tempDir, audit)).isInstanceOf(MojoFailureException.class);

        assertThat(read(tempDir)).extracting(f -> f.get("finalSeverity")).containsOnly("ERROR").hasSize(2);
    }

    @Test
    void without_the_file_nothing_is_suppressed(@TempDir final Path tempDir) {
        final var root = project(tempDir, null);

        assertThatThrownBy(() -> run(root, tempDir, config())).isInstanceOf(MojoFailureException.class);

        assertThat(read(tempDir)).extracting(f -> f.get("finalSeverity")).containsOnly("ERROR").hasSize(2);
    }

    /**
     * A line number beyond the int range is an invalid entry: it is skipped with a warning instead of aborting the
     * scan, and the other entries still apply.
     */
    @Test
    void line_number_out_of_range_is_skipped(@TempDir final Path tempDir) throws Exception {
        final var root = project(tempDir, "src/main/resources/keys.json:99999999999\nsrc/main/resources/keys.json\n");

        run(root, tempDir, config());

        assertThat(read(tempDir)).extracting(f -> f.get("finalSeverity")).containsOnly("SUPPRESSED").hasSize(2);
    }

    /**
     * The history is read-only from outside: entries are added through {@link Finding#log} and {@link Finding#note}.
     */
    @Test
    void score_history_is_read_only(@TempDir final Path tempDir) throws Exception {
        final var finding = scan(project(tempDir, null)).get(0);

        assertThatThrownBy(() -> finding.getScoreHistory().add("x")).isInstanceOf(UnsupportedOperationException.class);
        finding.note("x");
        assertThat(finding.getScoreHistory()).endsWith("x");
    }

    private static FacepalmConfig config() {
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        return config;
    }

    /**
     * Project with {@code src/main/resources/keys.json} and, unless {@code null}, a {@code .facepalmignore}.
     */
    private static Path project(final Path tempDir, final String ignoreFile) {
        try {
            final var root = tempDir.resolve("project");
            Files.createDirectories(root.resolve("src/main/resources"));
            Files.writeString(root.resolve("src/main/resources/keys.json"), KEYS_JSON);
            if (ignoreFile != null) {
                Files.writeString(root.resolve(SuppressionFile.FILE_NAME), ignoreFile);
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(final Path root, final Path tempDir, final FacepalmConfig config) throws Exception {
        TestContainer.create(config).getInstance(FacepalmRunner.class).run(root, tempDir.resolve("out"), "test");
    }

    private static List<Finding> scan(final Path root) throws Exception {
        return TestContainer.create(config()).getInstance(ScannerEngine.class).scan(root);
    }

    private static List<Map<String, Object>> read(final Path tempDir) {
        try {
            return new ObjectMapper().readValue(tempDir.resolve("out/facepalm-findings.json").toFile(),
                new TypeReference<List<Map<String, Object>>>() {
                });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fileName(final Map<String, Object> finding) {
        return Path.of(finding.get("fileAbsolutePath").toString()).getFileName().toString();
    }

    private static String random(final int length) {
        final var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
