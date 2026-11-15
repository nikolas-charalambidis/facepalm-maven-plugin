/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static dev.nichar.facepalm.configurator.CommaSeparatedConfigurator.COMMA_SEPARATED_CONFIGURATOR;

import com.google.inject.Guice;
import dev.nichar.facepalm.config.EngineConfig;
import dev.nichar.facepalm.config.EvaluatorConfig;
import dev.nichar.facepalm.config.PatternConfig;
import dev.nichar.facepalm.config.PostProcessorConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.FacepalmRunner;
import dev.nichar.facepalm.engine.ScoringStrategy;
import dev.nichar.facepalm.module.FacepalmConfigModule;
import dev.nichar.facepalm.module.FacepalmLogModule;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.util.Set;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.sisu.space.BeanScanning;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;

/**
 * Entry point for security scans during the {@code verify} phase.
 * Bootstraps the Guice engine and executes the scanning pipeline.
 */
@Mojo(name = "scan", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true, configurator = COMMA_SEPARATED_CONFIGURATOR)
public class FacepalmMojo extends AbstractMojo {

    /**
     * Metadata about the current plugin execution.
     */
    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor pluginDescriptor;

    /**
     * Maven project base directory.
     */
    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    /**
     * Build output directory for reports and artifacts.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    /**
     * Target directory for the security scan.
     */
    @Parameter(property = "root")
    private File root;

    /**
     * Thread pool size for parallel scanning. Defaults to CPU count.
     */
    @Parameter(property = "threads")
    private Integer threads;

    /**
     * Fail-safe limit for file sizes. Prevents memory exhaustion.
     */
    @Parameter(property = "maxFileSizeBytes", defaultValue = "5242880")
    private long maxFileSizeBytes;

    /**
     * Filter for binary files and non-text assets.
     */
    @Parameter(property = "skipBinaryRegex", defaultValue = ".*\\.(png|jpg|jpeg|gif|pdf|zip|jar|class|tar|gz|exe|dll)$")
    private String skipBinaryRegex;

    /**
     * User-defined exclusions for the scanner.
     */
    @Parameter(property = "skipDirs")
    private Set<String> skipDirs;

    /**
     * Log successfully analyzed files.
     */
    @Parameter(property = "showProcessed", defaultValue = "false")
    private boolean showProcessed;

    /**
     * Log files skipped by filters.
     */
    @Parameter(property = "showSkipped", defaultValue = "false")
    private boolean showSkipped;

    /**
     * Strategy used to combine risk and confidence into a final score.
     */
    @Parameter(property = "scoring.strategy", defaultValue = "WEIGHTED_QUADRATIC")
    private ScoringStrategy scoringStrategy;

    /**
     * Minimum score to trigger a critical failure.
     */
    @Parameter(property = "errorThreshold", defaultValue = "80")
    private int errorThreshold;

    /**
     * Minimum score to trigger a warning.
     */
    @Parameter(property = "warningThreshold", defaultValue = "40")
    private int warningThreshold;

    /**
     * Verbose logging for scoring decisions.
     */
    @Parameter(property = "showScoring", defaultValue = "false")
    private boolean showScoring;

    /**
     * Statistics for scan coverage and exclusions.
     */
    @Parameter(property = "showDetails", defaultValue = "false")
    private boolean showDetails;

    /**
     * Stop the build on high-risk findings.
     */
    @Parameter(property = "failOnError", defaultValue = "true")
    private boolean failOnError;

    /**
     * Stop the build on moderate-risk findings.
     */
    @Parameter(property = "failOnWarnings", defaultValue = "false")
    private boolean failOnWarnings;

    /**
     * Logic for evaluating leak legitimacy.
     */
    @Parameter
    private EvaluatorConfig evaluators = new EvaluatorConfig();

    /**
     * Custom detection patterns. Not supported yet (#79): configuring {@code <patterns><overrides>} fails the build,
     * because an empty list used to switch off all detection silently (#277). Leave it out to scan with the built-in
     * patterns.
     */
    @Parameter
    private PatternConfig patterns = new PatternConfig();

    /**
     * Logic for noise reduction and deduplication.
     */
    @Parameter
    private PostProcessorConfig postProcessing = new PostProcessorConfig();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        requireNoCustomPatterns(patterns);

        // Map Maven parameters to internal configuration.
        final var engine = new EngineConfig(threads, maxFileSizeBytes, skipBinaryRegex, skipDirs, showProcessed,
            showSkipped);
        final var scoring = new ScoringConfig(scoringStrategy, errorThreshold, warningThreshold, showScoring,
            showDetails, failOnError, failOnWarnings);
        final var effectiveConfig = new FacepalmConfig(engine, scoring, evaluators, postProcessing, patterns);

        // Discover components using Sisu indexing.
        final var space = new URLClassSpace(getClass().getClassLoader());

        // Initialize the Guice container.
        final var injector = Guice.createInjector(
            new WireModule(
                // Use pre-compiled JSR330 metadata for rapid startup.
                new SpaceModule(space, BeanScanning.INDEX),
                // Bridge Maven Log and state into the container.
                new FacepalmLogModule(getLog()),
                new FacepalmConfigModule(effectiveConfig)));

        // Inject components manually since Maven handles Mojo instantiation.
        final var runner = injector.getInstance(FacepalmRunner.class);
        final var config = injector.getInstance(FacepalmConfig.class);
        final var log = injector.getInstance(Log.class);

        if (runner == null || config == null || log == null) {
            throw new MojoExecutionException("Facepalm Mojo initialization failed: " +
                "runner=" + runner + ", config=" + config + ", log=" + log);
        }

        log.info("Starting facepalm-maven-plugin " + pluginDescriptor.getVersion());

        // Normalize the target scan path.
        final var rootFile = root != null ? root : baseDir;
        final var rootPath = rootFile.toPath().toAbsolutePath().normalize();

        try {
            runner.run(rootPath, outputDirectory.toPath(), pluginDescriptor.getVersion());
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw executionFailure(e);
        }
    }

    /**
     * Rejects a configured {@code <patterns><overrides>} (#277). Custom patterns are not supported yet (#79): a
     * non-empty list cannot be bound, and an empty one used to scan with zero patterns and pass every build.
     */
    static void requireNoCustomPatterns(@Nullable final PatternConfig patterns) throws MojoExecutionException {
        if (patterns != null && patterns.isConfigured()) {
            throw new MojoExecutionException("Facepalm scan failed: custom detection patterns (<patterns><overrides>) "
                + "are not supported yet, see https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/79."
                + " Remove the <patterns> configuration to scan with the built-in patterns.");
        }
    }

    /**
     * Wraps an unexpected scan error so that the build error states its cause (e.g. an invalid
     * {@code skipBinaryRegex} or an unwritable output directory) without rerunning Maven with {@code -e}.
     */
    @Nonnull
    static MojoExecutionException executionFailure(@Nonnull final Exception e) {
        final var reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        return new MojoExecutionException("Facepalm scan failed: " + reason, e);
    }
}
