/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.bench;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FacepalmLog;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.module.FacepalmConfigModule;
import dev.nichar.facepalm.module.FacepalmLogModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;

/**
 * Shared input of the benchmarks: the real dependency injection wiring and a deterministic corpus of realistic lines.
 * Secrets are generated from a fixed seed at runtime, so the sources contain no literal token that secret scanners
 * would flag, and every run measures the same input.
 */
final class BenchSupport {

    /**
     * Seed of the generated secrets and corpus, so every run measures the same input.
     */
    private static final long SEED = 245;

    /**
     * Characters of generated alphanumeric tokens.
     */
    private static final String ALPHANUMERIC = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    /**
     * Characters of generated base64 tokens.
     */
    private static final String BASE64 = ALPHANUMERIC + "+/";

    /**
     * Characters of generated AWS access key ids after the {@code AKIA} prefix.
     */
    private static final String AWS_KEY_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /**
     * Lines without secrets, as found in Java sources, configuration, data and logs. Most scanned lines look like
     * these, so the cost of not matching dominates.
     */
    private static final List<String> NOISE = List.of(
        "package com.acme.billing.service;",
        "import java.util.concurrent.ConcurrentHashMap;",
        "    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);",
        "        final var total = items.stream().mapToLong(Item::getPriceCents).sum();",
        "        if (customer.getTokenCount() > MAX_TOKENS && !request.isRetry()) {",
        "spring.datasource.url=jdbc:postgresql://db.internal:5432/billing",
        "spring.datasource.username=billing_app",
        "server.port=8080",
        "  timeout: 30s",
        "  - name: payment-gateway",
        "{\"id\": 42, \"name\": \"Widget\", \"tags\": [\"a\", \"b\"], \"price\": 19.99}",
        "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>",
        "2026-11-17T10:15:30.123Z INFO  [main] c.a.b.InvoiceService - Processed 120 invoices in 45 ms",
        "# Configure the retry policy before deploying to production",
        "https://shop.acme.test/api/v1/customers?page=2&size=50&sort=createdAt,desc",
        "password.min-length=12");

    private BenchSupport() {
    }

    /**
     * Container with the plugin's real wiring (as in {@code FacepalmCLI}) and a log that discards everything, so
     * logging does not dominate the measurements.
     */
    static Injector container(final FacepalmConfig config) {
        return Guice.createInjector(
            new WireModule(
                // One container per trial, so scanning without the deprecated BeanScanning.CACHE costs the same.
                new SpaceModule(new URLClassSpace(BenchSupport.class.getClassLoader()), SpaceModule.LOCAL_SCAN),
                new FacepalmLogModule(new QuietLog()),
                new FacepalmConfigModule(config)));
    }

    /**
     * Default configuration with a single scan thread, so the numbers do not depend on the runner's CPU count.
     */
    static FacepalmConfig config() {
        final var config = new FacepalmConfig();
        config.getEngine().setThreads(1);
        return config;
    }

    /**
     * Realistic lines: mostly noise, some secrets of specific and generic patterns, and one long minified line.
     */
    static List<String> lines() {
        final var random = new Random(SEED);
        final List<String> lines = new ArrayList<>(NOISE);
        lines.add("db.password=" + random(random, ALPHANUMERIC, 16));
        lines.add("aws_secret_access_key=" + random(random, BASE64, 40));
        lines.add("AWS_ACCESS_KEY_ID=AKIA" + random(random, AWS_KEY_ID, 16));
        lines.add("token=ghp_" + random(random, ALPHANUMERIC, 36));
        lines.add("  api_key: \"" + random(random, ALPHANUMERIC, 32) + "\"");
        lines.add("SLACK_BOT_TOKEN=xoxb-" + random(random, ALPHANUMERIC, 24));
        // Minified bundles and lock files have long lines without secrets.
        final var minified = new StringBuilder();
        while (minified.length() < 2000) {
            minified.append("var ").append(random(random, ALPHANUMERIC, 6)).append("=function(e){return e.")
                .append(random(random, ALPHANUMERIC, 12)).append("};");
        }
        lines.add(minified.toString());
        return List.copyOf(lines);
    }

    /**
     * A file of {@code lineCount} lines taken from {@link #lines()} in a shuffled order.
     */
    static List<String> fileLines(final int lineCount, final long seed) {
        final var source = lines();
        final var random = new Random(seed);
        final List<String> file = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            file.add(source.get(random.nextInt(source.size())));
        }
        return file;
    }

    /**
     * A {@link FileContext} of a production configuration file with the given lines.
     */
    static FileContext fileContext(final List<String> lines) {
        final var path = Path.of("src/main/resources/application.properties");
        return new FileContext(path, path, String.join("\n", lines), lines);
    }

    /**
     * Writes a project of {@code fileCount} files of {@code linesPerFile} lines below {@code root}: sources,
     * resources and configuration in several directories, plus a {@code .gitignore}.
     */
    static void writeProject(final Path root, final int fileCount, final int linesPerFile) throws IOException {
        final var directories = List.of("src/main/java/com/acme/billing", "src/main/resources",
            "src/test/java/com/acme",
            "config", "docs");
        final var extensions = List.of(".java", ".properties", ".java", ".yml", ".md");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".gitignore"), "*.log\n/local/\n");
        for (int i = 0; i < fileCount; i++) {
            final var directory = root.resolve(directories.get(i % directories.size()));
            Files.createDirectories(directory);
            final var file = directory.resolve("File" + i + extensions.get(i % extensions.size()));
            Files.writeString(file, String.join("\n", fileLines(linesPerFile, SEED + i)) + "\n");
        }
    }

    /**
     * Deletes a directory tree.
     */
    static void delete(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String random(final Random random, final String alphabet, final int length) {
        final var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    /**
     * Log that discards every message.
     */
    private static final class QuietLog implements FacepalmLog {

        @Override
        public void debug(final CharSequence content) {
            // Discarded.
        }

        @Override
        public void info(final CharSequence content) {
            // Discarded.
        }

        @Override
        public void warn(final CharSequence content) {
            // Discarded.
        }

        @Override
        public void warn(final CharSequence content, final Throwable error) {
            // Discarded.
        }

        @Override
        public void error(final CharSequence content) {
            // Discarded.
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }
    }
}
