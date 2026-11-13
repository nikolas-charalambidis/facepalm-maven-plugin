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
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
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
     * Usage shown when the arguments are invalid.
     */
    static final String USAGE = "Usage: FacepalmCLI [--debug] [--output <dir>] [<directory>]";

    /**
     * Maven metadata packaged in the plugin jar, used to read the version.
     */
    private static final String POM_PROPERTIES = "/META-INF/maven/dev.nichar/facepalm-maven-plugin/pom.properties";

    /**
     * Parsed command-line options.
     *
     * @param root directory to scan
     * @param output directory for the reports
     * @param debug whether to log at DEBUG level
     */
    record Options(Path root, Path output, boolean debug) {
    }

    /**
     * Bootstraps the Guice container and executes a scan on the specified directory.
     *
     * @param args Command-line arguments: {@code [--debug] [--output <dir>] [<directory>]}. The directory defaults to
     * the current one, the output to {@code <directory>/target}.
     */
    public static void main(final String[] args) throws MojoFailureException, MojoExecutionException {
        final var options = parse(args);

        // Assemble baseline configuration with default settings.
        final var engineConfig = new EngineConfig();
        // --debug also lists processed and skipped files (logged at DEBUG level).
        engineConfig.setShowProcessed(options.debug());
        engineConfig.setShowSkipped(options.debug());
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
                        new ConsoleLogger(options.debug() ? Logger.LEVEL_DEBUG : Logger.LEVEL_INFO,
                            "facepalm-cli"))),
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

        final var version = version();
        log.info("Starting facepalm-maven-plugin " + version);

        try {
            runner.run(options.root(), options.output(), version);
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw FacepalmMojo.executionFailure(e);
        }
    }

    /**
     * Parses {@code [--debug] [--output
     *
    <dir>
     * ] [<directory>]}.
     */
    static Options parse(final String[] args) throws MojoExecutionException {
        var debug = false;
        String output = null;
        String directory = null;
        for (int i = 0; i < args.length; i++) {
            final var arg = args[i];
            if ("--debug".equals(arg)) {
                debug = true;
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                output = args[++i];
            } else if (arg.startsWith("-") || directory != null) {
                throw new MojoExecutionException("Unexpected argument: " + arg + System.lineSeparator() + USAGE);
            } else {
                directory = arg;
            }
        }
        final var root = Paths.get(directory == null ? "." : directory).toAbsolutePath().normalize();
        final var outputDir = output == null ? root.resolve("target") : Paths.get(output).toAbsolutePath().normalize();
        return new Options(root, outputDir, debug);
    }

    /**
     * Plugin version from the jar's Maven metadata, or {@code unknown} when run from unpackaged classes.
     */
    static String version() {
        try (var in = FacepalmCLI.class.getResourceAsStream(POM_PROPERTIES)) {
            if (in == null) {
                return "unknown";
            }
            final var properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
