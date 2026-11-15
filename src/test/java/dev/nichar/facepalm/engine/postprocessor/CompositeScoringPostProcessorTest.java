/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.postprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.ScannerEngine;
import dev.nichar.facepalm.engine.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #275: the high-volume penalty must not let a file with many leaked secrets pass. Tokens are assembled at
 * runtime from random characters, so this file contains no literal token that other secret scanners would flag.
 */
class CompositeScoringPostProcessorTest {

    /**
     * Configuration with the default scoring.
     */
    private static final FacepalmConfig CONFIG = new FacepalmConfig();

    /**
     * Prefix of the score history entry of the high-volume penalty.
     */
    private static final String PENALTY = "High Volume File";

    @Test
    void many_aws_keys_in_one_file_still_fail(@TempDir final Path tempDir) throws Exception {
        final var random = new Random(275);
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            lines.add("AWS_ACCESS_KEY_ID_" + i + "=AKIA" + random(random, "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", 16));
        }

        final var findings = scan(tempDir, ".env", lines);

        assertThat(findings).filteredOn(f -> f.getPatternName().equals("AWS Access Key")).hasSize(16)
            .allSatisfy(f -> {
                assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith(PENALTY));
                assertThat(f.getSeverity(CONFIG.getScoring())).isEqualTo(Severity.ERROR);
            });
    }

    /**
     * A dataset with many generic matches at WARNING or above is still treated as noise.
     */
    @Test
    void dataset_with_many_generic_matches_still_gets_the_penalty(@TempDir final Path tempDir) throws Exception {
        final var random = new Random(15);
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("{\"id\": " + i + ", \"token\": \"" + random(random, "abcdefghijklmnop0123456789", 24) + "\"}");
        }

        final var findings = scan(tempDir, "src/main/resources/dataset.json", lines);

        assertThat(findings).hasSize(20).allSatisfy(f -> assertThat(f.getScoreHistory()).anyMatch(h -> h.startsWith(
            PENALTY)));
    }

    /**
     * INFO findings, such as references to environment variables, do not count toward the threshold.
     */
    @Test
    void info_findings_do_not_count_toward_the_threshold(@TempDir final Path tempDir) throws Exception {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            lines.add("server-password: CENTRAL_TOKEN_PASSWORD_" + i);
        }
        lines.add("api_key: " + random(new Random(7), "abcdefghijkmnopqrstuvwxyzABCDEFGH0123456789", 32));

        final var findings = scan(tempDir, "src/main/resources/application.yml", lines);

        assertThat(findings).hasSizeGreaterThanOrEqualTo(16)
            .allSatisfy(f -> assertThat(f.getScoreHistory()).noneMatch(h -> h.startsWith(PENALTY)));
    }

    private static List<Finding> scan(final Path tempDir, final String file, final List<String> lines)
            throws Exception {
        final var path = tempDir.resolve("project").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, String.join("\n", lines) + "\n");
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        return TestContainer.create(config).getInstance(ScannerEngine.class).scan(tempDir.resolve("project"));
    }

    private static String random(final Random random, final String alphabet, final int length) {
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
