/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parsing of {@code git diff --cached --raw -z} and the errors of {@link StagedFiles} (#236).
 */
class StagedFilesTest {

    /**
     * Staged blob id used in the parsed output.
     */
    private static final String BLOB = "a".repeat(40);

    @Test
    void parses_paths_with_spaces_and_skips_submodules(@TempDir final Path root) {
        final var zero = "0".repeat(40);
        final var raw = ":000000 100644 " + zero + " " + BLOB + " A\0my dir/server key.pem\0"
            + ":100644 100644 " + zero + " " + BLOB + " M\0app.properties\0"
            + ":160000 160000 " + zero + " " + BLOB + " M\0vendor/lib\0";

        final var blobs = StagedFiles.parse(root, raw);

        assertThat(blobs).containsOnlyKeys(root.resolve("my dir/server key.pem"), root.resolve("app.properties"));
        assertThat(blobs).containsValue(BLOB);
    }

    @Test
    void nothing_staged_is_an_empty_list(@TempDir final Path root) {
        assertThat(StagedFiles.parse(root, "")).isEmpty();
    }

    @Test
    void git_not_on_the_path_is_a_clear_error(@TempDir final Path root) {
        assertThatThrownBy(() -> StagedFiles.of(root, "facepalm-no-such-git"))
            .isInstanceOf(IOException.class)
            .hasMessageStartingWith("--staged needs git on the PATH");
    }
}
