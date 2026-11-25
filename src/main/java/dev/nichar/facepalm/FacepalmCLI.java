/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import com.google.inject.Guice;
import dev.nichar.facepalm.config.CommaSeparatedValues;
import dev.nichar.facepalm.config.EngineConfig;
import dev.nichar.facepalm.config.EvaluatorConfig;
import dev.nichar.facepalm.config.PatternConfig;
import dev.nichar.facepalm.config.PostProcessorConfig;
import dev.nichar.facepalm.config.ScoringConfig;
import dev.nichar.facepalm.engine.FacepalmLog;
import dev.nichar.facepalm.engine.FacepalmRunner;
import dev.nichar.facepalm.engine.ScanFailedException;
import dev.nichar.facepalm.engine.ScoringStrategy;
import dev.nichar.facepalm.module.FacepalmConfigModule;
import dev.nichar.facepalm.module.FacepalmLogModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.eclipse.sisu.space.BeanScanning;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;

/**
 * Command-line interface for running Facepalm scans outside the Maven lifecycle.
 * <p>
 * The options mirror the parameters of the {@code scan} goal under their kebab-case names, with the same defaults: the
 * defaults come from {@link EngineConfig} and {@link ScoringConfig}, and the options are mapped to them with the same
 * constructors as in {@link FacepalmMojo} (#235).
 */
@Command(name = "facepalm", mixinStandardHelpOptions = true, versionProvider = FacepalmCLI.Version.class, defaultValueProvider = FacepalmCLI.ConfigDefaults.class, sortOptions = false, usageHelpAutoWidth = true, description = "Scans a directory for leaked secrets, like the Maven goal facepalm:scan.", exitCodeListHeading = "%nExit codes:%n", exitCodeList = {
    "0:No finding fails the scan.",
    "1:Findings fail the scan (--fail-on-error, --fail-on-warnings).",
    "2:Invalid arguments, or the scan could not run." })
public class FacepalmCLI implements Callable<Integer> {

    /**
     * Exit code when no finding fails the scan.
     */
    static final int EXIT_OK = 0;

    /**
     * Exit code when findings fail the scan under the {@code failOnError} / {@code failOnWarnings} rules.
     */
    static final int EXIT_FINDINGS = 1;

    /**
     * Exit code for invalid arguments or a scan that could not run.
     */
    static final int EXIT_ERROR = 2;

    /**
     * Classpath resource with the plugin version, filtered by Maven when the resources are processed, so it exists
     * in IDE runs too (unlike the {@code pom.properties} of the packaged jar).
     */
    private static final String VERSION_RESOURCE = "/facepalm-version.properties";

    @Parameters(index = "0", arity = "0..1", paramLabel = "<directory>", description = "Directory to scan. Default: the current directory.")
    private Path directory;

    @Option(names = "--output", paramLabel = "<dir>", description = "Directory for facepalm-findings.json and facepalm-statistics.json. Default: <directory>/target.")
    private Path output;

    @Option(names = "--threads", paramLabel = "<n>", description = "Thread pool size for parallel scanning. Default: the number of CPUs.")
    private Integer threads;

    @Option(names = "--max-file-size-bytes", paramLabel = "<bytes>", description = "Files larger than this are skipped. Default: ${DEFAULT-VALUE}.")
    private long maxFileSizeBytes;

    @Option(names = "--skip-binary-regex", paramLabel = "<regex>", description = "Files whose path matches are skipped as binary. Default: ${DEFAULT-VALUE}.")
    private String skipBinaryRegex;

    @Option(names = "--skip-dirs", paramLabel = "<dir,...>", description = "Comma-separated directory names to skip. Replaces the defaults unless --use-default-skip-dirs.")
    private String skipDirs;

    @Option(names = "--use-default-skip-dirs", arity = "0..1", paramLabel = "<true|false>", description = "true: --skip-dirs adds to the default skip directories; false: it replaces them.")
    private Boolean useDefaultSkipDirs;

    @Option(names = "--show-processed", arity = "0..1", paramLabel = "<true|false>", description = "Log analyzed files (with --debug). Default: ${DEFAULT-VALUE}.")
    private boolean showProcessed;

    @Option(names = "--show-skipped", arity = "0..1", paramLabel = "<true|false>", description = "Log skipped files (with --debug). Default: ${DEFAULT-VALUE}.")
    private boolean showSkipped;

    @Option(names = "--suppressions", arity = "0..1", paramLabel = "<true|false>", description = "Honor facepalm:ignore markers and .facepalmignore. Default: ${DEFAULT-VALUE}.")
    private boolean suppressions;

    @Option(names = "--scoring-strategy", paramLabel = "<strategy>", description = "One of ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private ScoringStrategy scoringStrategy;

    @Option(names = "--error-threshold", paramLabel = "<score>", description = "Minimum score of an ERROR finding. Default: ${DEFAULT-VALUE}.")
    private int errorThreshold;

    @Option(names = "--warning-threshold", paramLabel = "<score>", description = "Minimum score of a WARNING finding. Default: ${DEFAULT-VALUE}.")
    private int warningThreshold;

    @Option(names = "--show-scoring", arity = "0..1", paramLabel = "<true|false>", description = "Log the score and its adjustments of each finding. Default: ${DEFAULT-VALUE}.")
    private boolean showScoring;

    @Option(names = "--show-details", arity = "0..1", paramLabel = "<true|false>", description = "Log discovery and exclusion statistics. Default: ${DEFAULT-VALUE}.")
    private boolean showDetails;

    @Option(names = "--fail-on-error", arity = "0..1", paramLabel = "<true|false>", description = "Exit with 1 if a finding reaches the error threshold. Default: ${DEFAULT-VALUE}.")
    private boolean failOnError;

    @Option(names = "--fail-on-warnings", arity = "0..1", paramLabel = "<true|false>", description = "Exit with 1 if a finding reaches the warning threshold. Default: ${DEFAULT-VALUE}.")
    private boolean failOnWarnings;

    @Option(names = "--debug", description = "Log at DEBUG level, list processed and skipped files, and print stack traces of errors.")
    private boolean debug;

    /**
     * Runs the CLI and stops the JVM with its exit code. Tests and IDE runs call {@link #run(String[])} instead.
     *
     * @throws ScanFailedException never since 1.2.0, where findings give exit code {@value #EXIT_FINDINGS}; still
     * declared, so callers that catch it keep compiling
     */
    public static void main(final String[] args) throws ScanFailedException {
        System.exit(run(args));
    }

    /**
     * Runs the CLI without stopping the JVM.
     *
     * @return {@value #EXIT_OK} if no finding fails the scan, {@value #EXIT_FINDINGS} if findings fail it,
     * {@value #EXIT_ERROR} for invalid arguments or a scan that could not run
     */
    public static int run(final String[] args) {
        return commandLine().execute(args);
    }

    /**
     * The command line with the exit codes of this CLI: usage errors are {@value #EXIT_ERROR} already in picocli.
     */
    @Nonnull
    static CommandLine commandLine() {
        final var commandLine = new CommandLine(new FacepalmCLI());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionExceptionHandler(FacepalmCLI::executionFailure);
        return commandLine;
    }

    /**
     * Scans {@link #root()} with {@link #config()}.
     */
    @Override
    public Integer call() throws Exception {
        final var injector = Guice.createInjector(
            new WireModule(
                // Perform a runtime scan of the classpath for @Named components.
                // Not strict, as the deprecated two-argument constructor: a class that fails to load is skipped.
                new SpaceModule(new URLClassSpace(FacepalmCLI.class.getClassLoader()), BeanScanning.CACHE, false),
                // Log to the console, without the Maven or Plexus logging API.
                new FacepalmLogModule(FacepalmLog.console(debug)),
                // Bind the active configuration to the container.
                new FacepalmConfigModule(config())));

        final var runner = injector.getInstance(FacepalmRunner.class);
        final var log = injector.getInstance(FacepalmLog.class);
        final var version = version();
        log.info("Starting facepalm-maven-plugin " + version);
        try {
            runner.run(root(), output(), version);
            return EXIT_OK;
        } catch (ScanFailedException e) {
            log.error(e.getMessage());
            return EXIT_FINDINGS;
        }
    }

    /**
     * The configuration of the parsed options, built with the same constructors as the Maven goal. {@code --debug}
     * also turns on {@code --show-processed} and {@code --show-skipped}, which log at DEBUG level.
     */
    @Nonnull
    FacepalmConfig config() {
        final var engine = new EngineConfig(threads, maxFileSizeBytes, skipBinaryRegex,
            CommaSeparatedValues.split(skipDirs), useDefaultSkipDirs, showProcessed || debug, showSkipped || debug,
            suppressions);
        final var scoring = new ScoringConfig(scoringStrategy, errorThreshold, warningThreshold, showScoring,
            showDetails, failOnError, failOnWarnings);
        return new FacepalmConfig(engine, scoring, new EvaluatorConfig(), new PostProcessorConfig(),
            new PatternConfig());
    }

    /**
     * The directory to scan: the argument, or the current directory.
     */
    @Nonnull
    Path root() {
        return (directory == null ? Path.of(".") : directory).toAbsolutePath().normalize();
    }

    /**
     * The report directory: {@code --output}, or {@code target} in the scanned directory.
     */
    @Nonnull
    Path output() {
        return output == null ? root().resolve("target") : output.toAbsolutePath().normalize();
    }

    /**
     * Prints why the scan could not run, without a stack trace unless {@code --debug}, and returns
     * {@value #EXIT_ERROR}.
     */
    @SuppressFBWarnings(value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE", justification = "The stack trace is printed only with --debug, which the user passes to see it (#235); it goes to their own terminal.")
    static int executionFailure(final Exception e, final CommandLine commandLine, final ParseResult parseResult) {
        final var err = commandLine.getErr();
        err.println("Facepalm scan failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        if (((FacepalmCLI) commandLine.getCommand()).debug) {
            e.printStackTrace(err);
        }
        err.flush();
        return EXIT_ERROR;
    }

    /**
     * Plugin version from {@value #VERSION_RESOURCE}, or {@code unknown} if the resources were not processed.
     */
    @Nonnull
    static String version() {
        try (var in = FacepalmCLI.class.getResourceAsStream(VERSION_RESOURCE)) {
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

    /**
     * Prints the plugin version for {@code --version}.
     */
    static final class Version implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {
                "facepalm " + version() };
        }
    }

    /**
     * Defaults of the options, taken from the configuration classes the Maven goal's defaults are checked against,
     * so the CLI and the goal cannot drift apart.
     */
    static final class ConfigDefaults implements CommandLine.IDefaultValueProvider {

        /**
         * Default value of each option by its name.
         */
        private final Map<String, String> defaults;

        ConfigDefaults() {
            final var engine = new EngineConfig();
            final var scoring = new ScoringConfig();
            defaults = Map.ofEntries(
                Map.entry("--max-file-size-bytes", String.valueOf(engine.getMaxFileSizeBytes())),
                Map.entry("--skip-binary-regex", engine.getSkipBinaryRegex()),
                Map.entry("--show-processed", String.valueOf(engine.isShowProcessed())),
                Map.entry("--show-skipped", String.valueOf(engine.isShowSkipped())),
                Map.entry("--suppressions", String.valueOf(engine.isSuppressions())),
                Map.entry("--scoring-strategy", scoring.getStrategy().name()),
                Map.entry("--error-threshold", String.valueOf(scoring.getErrorThreshold())),
                Map.entry("--warning-threshold", String.valueOf(scoring.getWarningThreshold())),
                Map.entry("--show-scoring", String.valueOf(scoring.isShowScoring())),
                Map.entry("--show-details", String.valueOf(scoring.isShowDetails())),
                Map.entry("--fail-on-error", String.valueOf(scoring.isFailOnError())),
                Map.entry("--fail-on-warnings", String.valueOf(scoring.isFailOnWarnings())));
        }

        @Override
        public String defaultValue(final ArgSpec argSpec) {
            return argSpec.isOption() ? defaults.get(((OptionSpec) argSpec).longestName()) : null;
        }
    }
}
