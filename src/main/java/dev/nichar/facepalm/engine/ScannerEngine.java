/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.evaluator.FindingEvaluator;
import dev.nichar.facepalm.engine.extractor.SecretExtractor;
import dev.nichar.facepalm.engine.postprocessor.FileFindingsPostProcessor;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Getter;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

/**
 * Concurrent engine for file discovery and secret extraction.
 * Orchestrates the parallel execution of the scanning pipeline across the project tree.
 */
@Named
@Singleton
public class ScannerEngine {

    private final Log log;

    @Inject
    private FacepalmConfig context;

    @Inject
    private List<SecretExtractor> extractors;

    @Inject
    private List<FindingEvaluator> evaluators;

    @Inject
    private List<FileFindingsPostProcessor> fileProcessors;

    @Getter
    private final ScanStatistics stats = new ScanStatistics();

    @Inject
    public ScannerEngine(@Nullable final Log log) {
        this.log = log != null ? log : new SystemStreamLog();
    }

    /**
     * Traverses the project root and executes parallel scans on qualified files.
     *
     * @param root Project directory to scan.
     * @return List of identified findings.
     */
    @Nonnull
    public List<Finding> scan(@Nonnull final Path root) throws InterruptedException, IOException {
        // Capture configuration on the main thread for thread safety.
        final var currentConfig = context;
        final var engineConfig = currentConfig.getEngine();

        final List<Callable<List<Finding>>> tasks = new ArrayList<>();
        // Walk the filesystem and identify candidate files.
        final var discoveredPaths = discover(root);
        log.info("Discovered " + discoveredPaths.size() + " files...");
        for (final var path : discoveredPaths) {
            stats.recordDiscovery();
            if (!shouldScan(path, stats)) {
                continue;
            }
            stats.recordScan(path);
            tasks.add(() -> processFile(root, path));
        }

        if (tasks.isEmpty()) {
            log.info("No files qualified to scan.");
            return Collections.emptyList();
        }

        log.info("Starting scan of " + tasks.size() + " files...");

        // Initialize thread pool for parallel processing.
        final var executor = Executors.newFixedThreadPool(engineConfig.getThreads());
        try {
            final CompletionService<List<Finding>> service = new ExecutorCompletionService<>(executor);
            for (final var task : tasks) {
                service.submit(task);
            }

            final List<Finding> allFindings = new ArrayList<>();
            for (final Callable<List<Finding>> ignored : tasks) {
                try {
                    allFindings.addAll(service.take().get());
                } catch (ExecutionException e) {
                    // processFile handles its own failures; this only covers errors such as OutOfMemoryError.
                    log.warn("Error processing file: " + e.getCause(), e.getCause());
                }
            }
            return allFindings;
        } catch (InterruptedException e) {
            // Restore the interrupt flag and abort: a partial result must not pass the build silently.
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // Shutdown the executor and await completion.
            executor.shutdownNow();
        }
    }

    /**
     * Collects regular files below the root. Directories listed in {@code skipDirs} are not entered at all, and
     * unreadable files or directories are logged and skipped instead of aborting the scan.
     *
     * <p>
     * Exclusions match only directories inside the scan root, so folders above the project (e.g. a checkout under
     * {@code /build/...}) and file names never exclude anything. Dotfiles such as {@code .env} are scanned; VCS and IDE
     * directories are excluded through {@code skipDirs}.
     */
    @Nonnull
    private List<Path> discover(@Nonnull final Path root) throws IOException {
        final var engineConfig = context.getEngine();
        final var skipDirs = engineConfig.getSkipDirs();
        final List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                if (isSkipDir(dir)) {
                    if (engineConfig.isShowSkipped()) {
                        log.debug("Skipping excluded directory: " + dir);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                // Follows symbolic links to files, like the previous Files.walk + Files::isRegularFile.
                if (attrs.isRegularFile() || Files.isRegularFile(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                // An unreadable directory fails before preVisitDirectory is called; excluded ones stay silent.
                if (isSkipDir(file)) {
                    return FileVisitResult.CONTINUE;
                }
                log.warn("Skipping unreadable path " + file + ": " + exc.getMessage());
                stats.recordExclusion(ExclusionReason.IO_ERROR);
                return FileVisitResult.CONTINUE;
            }

            private boolean isSkipDir(final Path dir) {
                return !dir.equals(root) && dir.getFileName() != null
                    && skipDirs.contains(dir.getFileName().toString());
            }
        });
        return files;
    }

    /**
     * Checks if a file qualifies for scanning based on size and type.
     */
    private boolean shouldScan(@Nonnull final Path path, @Nonnull final ScanStatistics stats) {
        final var engineConfig = context.getEngine();

        final var fileName = path.getFileName().toString();
        // Skip binary and non-text assets to reduce noise.
        if (fileName.toLowerCase(Locale.ROOT).matches(engineConfig.getSkipBinaryRegex())) {
            if (engineConfig.isShowSkipped()) {
                log.debug("Skipping binary file: " + path);
            }
            stats.recordExclusion(ExclusionReason.BINARY_FILE);
            return false;
        }

        try {
            // Skip large files to prevent OOM during regex matching.
            if (Files.size(path) > engineConfig.getMaxFileSizeBytes()) {
                if (engineConfig.isShowSkipped()) {
                    log.debug("Skipping large file: " + path);
                }
                stats.recordExclusion(ExclusionReason.SIZE_EXCEEDED);
                return false;
            }
        } catch (IOException e) {
            log.error("Could not determine size for: " + path);
            stats.recordExclusion(ExclusionReason.IO_ERROR);
            return false;
        }
        return true;
    }

    /**
     * Executes the extraction, evaluation, and post-processing pipeline for a single file.
     */
    @Nonnull
    private List<Finding> processFile(@Nonnull final Path root, @Nonnull final Path path) {
        final var engineConfig = context.getEngine();

        if (engineConfig.isShowProcessed()) {
            log.debug("Processing file: " + path);
        }
        try {
            // Load file into memory. Protected by size limits in shouldScan().
            final var content = TextDecoder.decode(Files.readAllBytes(path));
            if (content == null) {
                if (engineConfig.isShowSkipped()) {
                    log.debug("Skipping " + path + " - binary content");
                }
                return Collections.emptyList();
            }
            // Initialize file context for the scanning pipeline.
            final var context = new FileContext(path, root.relativize(path), content,
                Arrays.asList(content.split("\\R")));
            final List<Finding> fileFindings = new ArrayList<>();

            // Execute regex-based secret extraction.
            for (final var extractor : extractors) {
                fileFindings.addAll(extractor.extract(context));
            }

            // Refine findings using heuristic evaluators.
            for (final var finding : fileFindings) {
                for (final var evaluator : evaluators) {
                    evaluator.evaluate(finding, context);
                }
            }

            // Perform final file-level adjustments and deduplication.
            for (final var processor : fileProcessors) {
                processor.process(fileFindings, context);
            }

            return fileFindings;
        } catch (RuntimeException e) {
            // A failing extractor, evaluator or post-processor must not silently drop the file: report it.
            log.warn("Failed to scan " + path + ": " + e, e);
            stats.recordExclusion(ExclusionReason.PROCESSING_ERROR);
            return Collections.emptyList();
        } catch (IOException e) {
            log.warn("Skipping unreadable file " + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
