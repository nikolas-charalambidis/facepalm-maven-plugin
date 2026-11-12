/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.config.EngineConfig;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerEngineTest {

    /**
     * Dotfiles and dot-directories (.env, .aws/credentials) are the most common place for leaked secrets
     * and must be scanned.
     */
    @Test
    void scan_scans_dotfiles_and_dot_directories(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        Files.createDirectories(root.resolve(".aws"));
        Files.writeString(root.resolve(".env"), "AWS_SECRET_KEY=abc");
        Files.writeString(root.resolve(".aws/credentials"), "aws_secret_access_key=abc");

        final ScannerEngine scanner = scannerWithMinimalContext();
        scanner.scan(root);

        Assertions.assertEquals(2L, scanner.getStats().getFilesScanned().sum());
    }

    @Test
    void scan_excludes_default_skip_dirs_inside_root(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".git/config"), "[core]");
        Files.writeString(root.resolve("target/classes/app.properties"), "token=abc123");
        Files.writeString(root.resolve("src/app.properties"), "token=abc123");

        final ScannerEngine scanner = scannerWithMinimalContext();
        scanner.scan(root);

        Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
        Assertions.assertEquals(2L, excluded(scanner, ExclusionReason.REGEX_MATCH));
    }

    /**
     * Directories above the scan root must not exclude anything, e.g. a checkout under /build/... or .../out/....
     */
    @Test
    void scan_ignores_skip_dirs_outside_scan_root(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("build").resolve("out").resolve("project");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.properties"), "token=abc123");

        final ScannerEngine scanner = scannerWithMinimalContext();
        scanner.scan(root);

        Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
        Assertions.assertEquals(0L, excluded(scanner, ExclusionReason.REGEX_MATCH));
    }

    /**
     * A project inside a dot-directory (e.g. ~/.dotfiles) must be scanned.
     */
    @Test
    void scan_scans_root_inside_dot_directory(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve(".dotfiles");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.properties"), "token=abc123");

        final ScannerEngine scanner = scannerWithMinimalContext();
        scanner.scan(root);

        Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
    }

    /**
     * skipDirs names only exclude directories, not files with the same name.
     */
    @Test
    void scan_does_not_exclude_files_named_like_skip_dirs(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build"), "token=abc123");

        final ScannerEngine scanner = scannerWithMinimalContext();
        scanner.scan(root);

        Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
    }

    private static long excluded(final ScannerEngine scanner, final ExclusionReason reason) {
        return scanner.getStats().getExclusionBreakdown().getOrDefault(reason, new LongAdder()).sum();
    }

    private ScannerEngine scannerWithMinimalContext() throws Exception {
        final ScannerEngine scanner = new ScannerEngine(new SystemStreamLog());
        final FacepalmConfig config = new FacepalmConfig();
        final EngineConfig engine = new EngineConfig();
        engine.setThreads(1);
        config.setEngine(engine);
        setField(scanner, "context", config);
        setField(scanner, "extractors", List.of());
        setField(scanner, "evaluators", List.of());
        setField(scanner, "fileProcessors", List.of());
        return scanner;
    }

    private static void setField(final Object instance, final String fieldName, final Object value)
            throws ReflectiveOperationException {
        final Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
