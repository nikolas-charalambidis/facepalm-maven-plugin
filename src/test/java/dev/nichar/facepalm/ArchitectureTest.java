/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.nichar.facepalm.engine.FacepalmRunner;
import dev.nichar.facepalm.engine.ScoringStrategy;
import org.apache.maven.plugin.Mojo;
import org.junit.jupiter.api.Test;

/**
 * Layer boundaries of the production code (#343), so they hold beyond code review. Only the Mojos, the CLI entry point,
 * {@code module/} and {@code configurator/} may touch the Maven, Guice and Plexus APIs; the scanning core stays
 * independent of them and of reporting.
 */
class ArchitectureTest {

    /**
     * Root package of the plugin.
     */
    private static final String ROOT = "dev.nichar.facepalm";

    /**
     * Production classes only: the rules are about the shipped code, not about how the tests are wired.
     */
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(ROOT);

    @Test
    void patterns_and_config_do_not_depend_on_the_maven_api() {
        noClasses().that().resideInAnyPackage(ROOT + ".pattern..", ROOT + ".config..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.maven..")
            .check(CLASSES);
    }

    @Test
    void patterns_depend_on_no_other_plugin_package() {
        noClasses().that().resideInAPackage(ROOT + ".pattern..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT, ROOT + ".config..", ROOT + ".engine..",
                ROOT + ".report..", ROOT + ".module..", ROOT + ".configurator..")
            .check(CLASSES);
    }

    @Test
    void patterns_extraction_and_evaluation_do_not_depend_on_reporting() {
        noClasses().that().resideInAnyPackage(ROOT + ".pattern..", ROOT + ".engine.extractor..",
            ROOT + ".engine.evaluator..", ROOT + ".engine.postprocessor..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".report..")
            .check(CLASSES);
    }

    /**
     * Sisu only sees JSR-330 annotations: {@code jakarta.inject} would silently break the wiring (see CLAUDE.md). If
     * #167 (Guice 7) moves the plugin's own components to {@code jakarta.inject}, this rule changes with that decision.
     */
    @Test
    void injection_uses_javax_inject_only() {
        noClasses().should().dependOnClassesThat().resideInAPackage("jakarta.inject..")
            .because("the Maven Sisu container only sees javax.inject")
            .check(CLASSES);
    }

    @Test
    void guice_is_used_only_by_the_entry_points_and_module() {
        noClasses().that().resideOutsideOfPackages(ROOT, ROOT + ".module..")
            .should().dependOnClassesThat().resideInAPackage("com.google.inject..")
            .check(CLASSES);
    }

    @Test
    void plexus_is_used_only_by_the_entry_points_module_and_configurator() {
        noClasses().that().resideOutsideOfPackages(ROOT, ROOT + ".module..", ROOT + ".configurator..")
            .should().dependOnClassesThat().resideInAPackage("org.codehaus.plexus..")
            .check(CLASSES);
    }

    /**
     * The CLI wraps its own errors (#233), so it can run where no Mojo class can be loaded (#232).
     */
    @Test
    void cli_does_not_depend_on_the_mojos() {
        noClasses().that().haveFullyQualifiedName(FacepalmCLI.class.getName())
            .should().dependOnClassesThat().areAssignableTo(Mojo.class)
            .check(CLASSES);
    }

    /**
     * The engine and the reporter log through {@code FacepalmLog} and fail with {@code ScanFailedException} (#233); the
     * Mojos adapt both to the Maven API.
     */
    @Test
    void engine_and_report_do_not_depend_on_the_maven_api() {
        noClasses().that().resideInAnyPackage(ROOT + ".engine..", ROOT + ".report..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.maven..")
            .check(CLASSES);
    }

    /**
     * The standalone jar of #232 has no {@code maven-plugin-api}, {@code maven-core} or Plexus on its classpath, so
     * nothing the CLI reaches, directly or through the engine and the Guice modules, may use them (#233).
     */
    @Test
    void cli_runs_without_the_maven_and_plexus_api() {
        noClasses().that().belongToAnyOf(FacepalmCLI.class)
            .should().transitivelyDependOnClassesThat()
            .resideInAnyPackage("org.apache.maven..", "org.codehaus.plexus..")
            .because("the cli jar of #232 runs with only a JRE (#234)")
            .check(CLASSES);
    }

    /**
     * Only the entry points may know them: the scanning core is called by the Mojos and the CLI, never the other way
     * round.
     */
    @Test
    void only_the_entry_points_depend_on_the_entry_points() {
        noClasses().that().resideOutsideOfPackage(ROOT)
            .should().dependOnClassesThat()
            .belongToAnyOf(FacepalmMojo.class, FacepalmReportMojo.class, FacepalmCLI.class)
            .check(CLASSES);
    }

    /**
     * Command-line parsing (picocli, #235) stays in the CLI entry point; the Mojos and the scanning core get their
     * options as {@code *Config} objects.
     */
    @Test
    void command_line_parsing_stays_in_the_cli() {
        noClasses().that().doNotBelongToAnyOf(FacepalmCLI.class)
            .should().dependOnClassesThat().resideInAPackage("picocli..")
            .because("#235 shares the options with the Mojo through the *Config objects, not through picocli")
            .check(CLASSES);
    }

    /**
     * Two known edges close cycles today and are ignored, so that any new cycle fails:
     * <ul>
     * <li>{@code config -> engine}: {@code ScoringConfig} uses the {@link ScoringStrategy} enum of the engine;</li>
     * <li>{@code engine -> report}: {@link FacepalmRunner} orchestrates the scan and the reports, while the reports
     * read the engine's findings.</li>
     * </ul>
     * Remove an ignore once its edge is gone (for example {@code ScoringStrategy} moved to {@code config}, or the
     * runner moved out of {@code engine} with #233).
     */
    @Test
    void top_level_packages_are_free_of_cycles() {
        slices().matching(ROOT + ".(*)..")
            .should().beFreeOfCycles()
            .ignoreDependency(resideInAPackage(ROOT + ".config.."), belongToAnyOf(ScoringStrategy.class))
            .ignoreDependency(belongToAnyOf(FacepalmRunner.class), resideInAPackage(ROOT + ".report.."))
            .check(CLASSES);
    }
}
