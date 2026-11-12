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
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
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
        try (final var paths = Files.walk(root)) {
            final var discoveredPaths = paths.filter(Files::isRegularFile).toList();
            log.info("Discovered " + discoveredPaths.size() + " files...");
            for (final var path : discoveredPaths) {
                stats.recordDiscovery();
                if (!shouldScan(root, path, stats)) {
                    continue;
                }
                stats.recordScan(path);
                tasks.add(() -> processFile(root, path));
            }
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
                } catch (Exception e) {
                    log.error("Error processing file", e);
                }
            }
            return allFindings;
        } finally {
            // Shutdown the executor and await completion.
            executor.shutdown();
        }
    }

    /**
     * Checks if a file qualifies for scanning based on size, type, and exclusion rules.
     */
    private boolean shouldScan(@Nonnull final Path root,
                               @Nonnull final Path path,
                               @Nonnull final ScanStatistics stats) {
        final var engineConfig = context.getEngine();

        // Match exclusions only against directories inside the scan root, so folders above the project
        // (e.g. a checkout under /build/...) and the file name itself never exclude a file.
        // Dotfiles such as .env are scanned; VCS and IDE directories are excluded through skipDirs.
        final var skipDirs = engineConfig.getSkipDirs();
        final var parent = root.relativize(path).getParent();
        if (parent != null) {
            for (final var element : parent) {
                if (skipDirs.contains(element.toString())) {
                    if (engineConfig.isShowSkipped()) {
                        log.debug("Skipping excluded directory: " + path);
                    }
                    stats.recordExclusion(ExclusionReason.REGEX_MATCH);
                    return false;
                }
            }
        }

        final var fileName = path.getFileName().toString();
        // Skip binary and non-text assets to reduce noise.
        if (fileName.toLowerCase().matches(engineConfig.getSkipBinaryRegex())) {
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
            final var content = Files.readString(path);
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
        } catch (MalformedInputException e) {
            if (engineConfig.isShowSkipped()) {
                log.warn("Skipping " + path + " - file is not valid UTF-8 text:" + e.getMessage());
            }
            return Collections.emptyList();
        } catch (IOException e) {
            log.error("Failed to read " + path + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
