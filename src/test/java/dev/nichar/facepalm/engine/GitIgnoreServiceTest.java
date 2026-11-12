/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Robustness of {@code .gitignore} loading (issue #64). Files are nested one level, because root-level matching is
 * part of the spec-compliance work in #9.
 */
class GitIgnoreServiceTest {

    @Test
    void invalid_glob_lines_are_skipped_and_valid_lines_still_apply(@TempDir final Path tempDir) throws Exception {
        final var root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".gitignore"), "foo[\na{b\n*.log\n");

        final var service = new GitIgnoreService(new SystemStreamLog());
        service.loadAllGitIgnores(root);

        assertTrue(service.isIgnored(root.resolve("logs/app.log")));
        assertFalse(service.isIgnored(root.resolve("logs/app.properties")));
    }

    @Test
    void non_utf8_gitignore_is_read(@TempDir final Path tempDir) throws Exception {
        final var root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.write(root.resolve(".gitignore"), "# Größe\n*.log\n".getBytes(StandardCharsets.ISO_8859_1));

        final var service = new GitIgnoreService(new SystemStreamLog());
        service.loadAllGitIgnores(root);

        assertTrue(service.isIgnored(root.resolve("logs/app.log")));
    }

    @Test
    void project_directory_with_glob_metacharacters_is_matched_literally(@TempDir final Path tempDir)
            throws Exception {
        final var root = tempDir.resolve("my[app]{x}");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".gitignore"), "*.log\n");

        final var service = new GitIgnoreService(new SystemStreamLog());
        service.loadAllGitIgnores(root);

        assertTrue(service.isIgnored(root.resolve("logs/app.log")));
    }

    @Test
    void escape_glob_escapes_metacharacters() {
        assertEquals("/a/my\\[app\\]/\\{x\\}/\\*\\?", GitIgnoreService.escapeGlob("/a/my[app]/{x}/*?"));
    }
}
