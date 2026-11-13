/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.TestContainer;
import dev.nichar.facepalm.config.EngineConfig;
import dev.nichar.facepalm.engine.evaluator.FindingEvaluator;
import dev.nichar.facepalm.engine.extractor.SecretExtractor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
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

        // Excluded directories are not entered, so their files are neither discovered nor scanned.
        Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
        Assertions.assertEquals(1L, scanner.getStats().getFilesDiscovered().sum());
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

    /**
     * An unreadable directory must be skipped with a warning instead of aborting the whole scan (issue #64).
     */
    @Test
    void scan_continues_past_unreadable_directory(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        final Path locked = root.resolve("pgdata");
        Files.createDirectories(locked);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.properties"), "token=abc123");
        Assumptions.assumeTrue(locked.toFile().setReadable(false, false), "Cannot change permissions");
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "Running with privileges that ignore permissions");

            final ScannerEngine scanner = scannerWithMinimalContext();
            scanner.scan(root);

            Assertions.assertEquals(1L, scanner.getStats().getFilesScanned().sum());
            Assertions.assertEquals(1L, excluded(scanner, ExclusionReason.IO_ERROR));
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    /**
     * Directories in skipDirs are not entered at all, so an unreadable excluded directory causes no error.
     */
    @Test
    void scan_does_not_enter_skip_dirs(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        final Path locked = root.resolve("node_modules");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("lib.js"), "token=abc123");
        Assumptions.assumeTrue(locked.toFile().setReadable(false, false), "Cannot change permissions");
        try {
            final ScannerEngine scanner = scannerWithMinimalContext();
            scanner.scan(root);

            Assertions.assertEquals(0L, scanner.getStats().getFilesScanned().sum());
            Assertions.assertEquals(0L, excluded(scanner, ExclusionReason.IO_ERROR));
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    /**
     * A failure while analysing one file is reported and counted; findings of other files are kept (issue #69).
     */
    @Test
    void scan_keeps_other_findings_when_one_file_fails(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("good.properties"), "token=abc123");
        Files.writeString(root.resolve("bad.properties"), "token=abc123");

        final ScannerEngine scanner = scannerWithMinimalContext();
        final SecretExtractor extractor = context -> List.of(Finding.builder()
            .patternName("Test")
            .deduplicationHash(context.getPath().toString())
            .secretValue("abc123")
            .context(context)
            .build());
        final FindingEvaluator failing = (finding, context) -> {
            if (context.getPath().endsWith("bad.properties")) {
                throw new IllegalStateException("boom");
            }
        };
        setField(scanner, "extractors", List.of(extractor));
        setField(scanner, "evaluators", List.of(failing));

        final List<Finding> findings = scanner.scan(root);

        Assertions.assertEquals(1, findings.size());
        Assertions.assertTrue(findings.get(0).getContext().getPath().endsWith("good.properties"));
        Assertions.assertEquals(1L, excluded(scanner, ExclusionReason.PROCESSING_ERROR));
    }

    /**
     * Under a Turkish default locale, "LOGO.GIF".toLowerCase() is "logo.gıf" (dotless i), which escaped the binary
     * filter (issue #68).
     */
    @Test
    void binary_filter_is_locale_independent(@TempDir final Path tempDir) throws Exception {
        final Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("LOGO.GIF"), "GIF89a");

        final Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            final ScannerEngine scanner = scannerWithMinimalContext();
            scanner.scan(root);

            Assertions.assertEquals(1L, excluded(scanner, ExclusionReason.BINARY_FILE));
            Assertions.assertEquals(0L, scanner.getStats().getFilesScanned().sum());
        } finally {
            Locale.setDefault(original);
        }
    }

    private static long excluded(final ScannerEngine scanner, final ExclusionReason reason) {
        return scanner.getStats().getExclusionBreakdown().getOrDefault(reason, new LongAdder()).sum();
    }

    /**
     * Engine wired by the real container (so new dependencies are injected), with an empty pipeline: these tests
     * cover discovery and exclusions only.
     */
    private ScannerEngine scannerWithMinimalContext() throws Exception {
        final FacepalmConfig config = new FacepalmConfig();
        final EngineConfig engine = new EngineConfig();
        engine.setThreads(1);
        config.setEngine(engine);
        final ScannerEngine scanner = TestContainer.create(config).getInstance(ScannerEngine.class);
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
