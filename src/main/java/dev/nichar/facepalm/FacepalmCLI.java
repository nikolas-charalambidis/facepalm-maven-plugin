/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import com.google.inject.Guice;
import dev.nichar.facepalm.config.EngineConfig;
import dev.nichar.facepalm.config.EvaluatorConfig;
import dev.nichar.facepalm.config.PatternConfig;
import dev.nichar.facepalm.config.PostProcessorConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.FacepalmRunner;
import dev.nichar.facepalm.module.FacepalmConfigModule;
import dev.nichar.facepalm.module.FacepalmLogModule;
import java.nio.file.Paths;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.eclipse.sisu.space.BeanScanning;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;

/**
 * Command-line interface for running Facepalm scans outside the Maven lifecycle.
 */
public class FacepalmCLI {

    /**
     * Bootstraps the Guice container and executes a scan on the specified directory.
     *
     * @param args Command-line arguments. args[0] is the target directory (default: current).
     */
    public static void main(final String[] args) throws MojoFailureException, MojoExecutionException {

        // Assemble baseline configuration with default settings.
        final var engineConfig = new EngineConfig();
        final var scoringConfig = new ScoringConfig();
        final var evaluatorConfig = new EvaluatorConfig();
        final var postProcessorConfig = new PostProcessorConfig();
        final var patternConfig = new PatternConfig();

        final var effectiveConfig = new FacepalmConfig(engineConfig, scoringConfig, evaluatorConfig,
            postProcessorConfig, patternConfig);

        // Discover injectable components from the current classpath.
        final var space = new URLClassSpace(FacepalmCLI.class.getClassLoader());

        // Initialize the dependency injection container.
        final var injector = Guice.createInjector(
            new WireModule(
                // Perform a runtime scan of the classpath for @Named components.
                new SpaceModule(space, BeanScanning.CACHE),
                // Bridge Maven logging to the console.
                new FacepalmLogModule(
                    new DefaultLog(
                        new ConsoleLogger(Logger.LEVEL_DEBUG, "facepalm-cli"))),
                // Bind the active configuration to the container.
                new FacepalmConfigModule(effectiveConfig)));

        // Resolve component instances for the scanning workflow.
        final var runner = injector.getInstance(FacepalmRunner.class);
        final var config = injector.getInstance(FacepalmConfig.class);
        final var log = injector.getInstance(Log.class);

        if (runner == null || config == null || log == null) {
            throw new MojoExecutionException("Facepalm Mojo initialization failed: " +
                "runner=" + runner + ", config=" + config + ", log=" + log);
        }

        // Normalize the target scan path from arguments.
        final var root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        try {
            runner.run(root, Paths.get("target"), "SNAPSHOT");
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw FacepalmMojo.executionFailure(e);
        }
    }
}
