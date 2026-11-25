/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.ScannerEngine;
import dev.nichar.facepalm.engine.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Issue #386: values that cannot be secrets (placeholders, predictable dummies, format descriptions and code
 * expressions) failed the build. Each case runs the full pipeline on one line, so every evaluator takes part. Real
 * values in the same positions stay reported. Real tokens are assembled at runtime, so this file contains no literal
 * token that other secret scanners would flag.
 */
class NonSecretValuesTest {

    /**
     * Configuration with the default scoring.
     */
    private static final FacepalmConfig CONFIG = new FacepalmConfig();

    static Stream<Arguments> nonSecrets() {
        return Stream.of(
            Arguments.of("k8s/deployment.yaml", "  value: postgres://$(DB_USER):$(DB_PASSWORD)@$(DB_HOST):5432/app"),
            Arguments.of("src/main/resources/application.yaml", "payments.token: \"#{vault.payments.token}\""),
            Arguments.of("src/main/resources/application.yaml", "payments.password: \"@{payments.password}\""),
            Arguments.of("src/main/resources/application.yaml", "payments.api-secret: ~{payments.api-secret}~"),
            Arguments.of("src/main/resources/application.yaml", "payments.password: \"%(payments_password)s\""),
            Arguments.of("src/main/resources/application.properties",
                "app.jwt.secret=A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"),
            Arguments.of("src/main/resources/application.properties",
                "app.signing.secret=0123456789abcdef0123456789abcdef"),
            Arguments.of("src/main/resources/application.properties", "app.client.secret=AAAAAAAAAAAAAAAA"),
            Arguments.of("src/main/resources/application.properties", "app.client.secret=qwertyuiopasdfghjkl"),
            Arguments.of("config/pgpass.conf", "#hostname:port:database:username:password:schema:role"),
            Arguments.of("postman/collection.json",
                "        \"var token = responseHeader.split(\\\" \\\")[1].trim();\","));
    }

    @ParameterizedTest
    @MethodSource("nonSecrets")
    void non_secret_values_drop_to_info(final String file, final String line, @TempDir final Path tempDir)
            throws Exception {
        final var findings = scan(tempDir, file, line);

        assertThat(findings).isNotEmpty().allSatisfy(f -> assertThat(severity(f))
            .as("%s -> %s %s", line, f.getSecretValue(), f.getScoreHistory())
            .isEqualTo(Severity.INFO));
    }

    static Stream<Arguments> realValues() {
        return Stream.of(
            Arguments.of("k8s/deployment.yaml", "  value: postgres://app:" + "Zq8vLm3NpR7tXw2Y" + "@db/app"),
            Arguments.of("src/main/resources/application.properties",
                "app.jwt.secret=" + "Hq3vX9LmP2tRw8KzN5cYb7Fd1Gs4Ja6U"),
            Arguments.of("src/main/resources/application.properties", "aws.access.key.id=AKIA" + "Q7RT2WXZ8VLM3NPK"),
            Arguments.of("src/main/resources/application.properties",
                "github.token=ghp_" + "Qw3Er5Ty7Ui9Op2As4Df6Gh8Jk0Lz1Xc3Vb5"));
    }

    @ParameterizedTest
    @MethodSource("realValues")
    void real_values_stay_reported(final String file, final String line, @TempDir final Path tempDir)
            throws Exception {
        final var findings = scan(tempDir, file, line);

        assertThat(findings).isNotEmpty().allSatisfy(f -> assertThat(severity(f))
            .as("%s -> %s", line, f.getScoreHistory())
            .isIn(Severity.WARNING, Severity.ERROR));
    }

    private static List<Finding> scan(final Path tempDir, final String file, final String line) throws Exception {
        final var path = tempDir.resolve("project").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, line + "\n");
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        return TestContainer.create(config).getInstance(ScannerEngine.class).scan(tempDir.resolve("project"));
    }

    private static Severity severity(final Finding finding) {
        return finding.getSeverity(CONFIG.getScoring());
    }
}
