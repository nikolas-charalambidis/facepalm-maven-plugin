# Requirements

These requirements describe the current development branch. IDs are stable references, not priorities.
Each requirement links to executable evidence; linked test-class Javadoc points back here. Tests protect covered
scenarios, not all possible inputs. See [architecture](architecture.md), [ADRs](adr/README.md) and
[roadmap](roadmap.md) for design, rationale and future work.

<a id="fr-01"></a>

## FR-01: Detect supported secret formats

Built-in patterns extract secret values, including multiline private keys, while rejecting covered negative samples. Detection is a guardrail, not a guarantee that every possible secret is recognized.

Verification: [SecretPatternRegistryTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/pattern/SecretPatternRegistryTest.java), [SecretPatternSamplesTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/pattern/SecretPatternSamplesTest.java), [RegexSecretExtractorTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/extractor/RegexSecretExtractorTest.java).

<a id="fr-02"></a>

## FR-02: Discover and filter local text

Scan eligible dotfiles and source files; apply configured directory, size and binary filters, decode supported text, and report exclusions. A source package named like a build directory is not automatically build output.

Verification: [FacepalmRunnerTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FacepalmRunnerTest.java) (file-size exclusion), [ScannerEngineTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/ScannerEngineTest.java), [TextDecoderTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/TextDecoderTest.java).

<a id="fr-03"></a>

## FR-03: Scan staged content

CLI --staged scans eligible staged additions and modifications using index content, including renames and partially staged files. Deleted files and unstaged-only secrets do not enter this scan. Git is required.

Verification: [StagedScanTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/StagedScanTest.java), [StagedFilesTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/StagedFilesTest.java).

<a id="fr-04"></a>

## FR-04: Score and classify findings

Apply evaluator and file-level adjustments to risk/confidence, clamping each to 0–100, then use the configured strategy and inclusive severity thresholds. Defaults: WEIGHTED_QUADRATIC, ERROR >= 80 and WARNING >= 40. Ignored untracked files receive an exposure discount; tracked files do not.

Verification: [FindingTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FindingTest.java) (score clamping and adjustment history), [ScoringStrategyTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/ScoringStrategyTest.java), [SeverityThresholdTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/SeverityThresholdTest.java), [CompositeScoringPostProcessorTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/postprocessor/CompositeScoringPostProcessorTest.java), [GitIgnoreServiceTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/GitIgnoreServiceTest.java).

<a id="fr-05"></a>

## FR-05: Suppress explicit findings

Apply .facepalmignore and inline suppression controls after scoring. Suppressed findings do not fail the scan; unused entries and markers produce diagnostics.

Verification: [InlineSuppressorTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/InlineSuppressorTest.java), [SuppressionFileTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/SuppressionFileTest.java).

<a id="fr-06"></a>

## FR-06: Enforce the selected failure policy

By default ERROR findings fail and WARNING findings do not. failOnWarnings counts ERROR too, independently of failOnError. With failOnScanError enabled, failed paths also fail the scan; otherwise scanning continues with warnings. Invalid roots/configuration and output failures remain errors.

Verification: [FacepalmRunnerTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FacepalmRunnerTest.java), [FacepalmMojoTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmMojoTest.java), [FacepalmCLITest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmCLITest.java).

<a id="fr-07"></a>

## FR-07: Persist and render results

Write masked findings and separate statistics JSON before a policy failure. Delete stale output before starting. The Maven report goal renders saved findings rather than rescanning; missing/empty scan data is handled explicitly.

Verification: [FacepalmRunnerTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FacepalmRunnerTest.java), [ScanStatisticsReportTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/report/ScanStatisticsReportTest.java), [FacepalmReportMojoTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmReportMojoTest.java).

<a id="fr-08"></a>

## FR-08: Share configuration across entry points

Maven and CLI defaults agree. CLI help/version are available; finding-policy failure returns 1, success 0, and usage/execution errors 2. Comma-separated Maven collections remain supported.

Verification: [DefaultsConsistencyTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/config/DefaultsConsistencyTest.java), [FacepalmCLITest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmCLITest.java), [FacepalmCliIT](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmCliIT.java), [CommaSeparatedSetConverterTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/configurator/CommaSeparatedSetConverterTest.java).

<a id="nfr-01"></a>

## NFR-01: Support the documented environments

Runtime minimums are Java 17 and Maven 3.6.3; building this repository needs Maven 3.9+. The CI compatibility matrix exercises JDK 17/21/25 and Maven 3.6.3/3.9.16/4.0.0-rc-7 on Linux, plus Java 17/Maven 3.9.16 on Windows/macOS. Maven 4 checks are advisory until GA. A matrix entry is evidence only when its run passes.

Verification: [FacepalmMojoIT](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmMojoIT.java), [FacepalmCliIT](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/FacepalmCliIT.java).

<a id="nfr-02"></a>

## NFR-02: Keep scanning local

Scanner production code must not directly depend on networking APIs. It reads local files and runs local Git commands; it does not upload findings or validate credentials remotely. The architecture guard covers direct dependencies, not every possible behavior of third-party libraries or subprocesses. Maven dependency downloads, site generation and link checks are outside this requirement.

Verification: [ArchitectureTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/ArchitectureTest.java).

<a id="nfr-03"></a>

## NFR-03: Protect secrets in output

Mask secret values and surrounding snippets in JSON/logs/reports, and escape repository-controlled HTML content. At most a quarter of a value is revealed, capped at four characters per end; values shorter than eight characters are fully masked.

Verification: [FindingTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FindingTest.java), [SnippetMaskingTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/extractor/SnippetMaskingTest.java), [ReporterTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/report/ReporterTest.java).

<a id="nfr-04"></a>

## NFR-04: Bound analysis work where supported

Use configured thread count and file-size limits, binary exclusions and regex backtracking safeguards. Tests cover known adversarial regex inputs; benchmarks provide measurements, not a throughput SLA or a total-memory bound. Open memory-exhaustion work remains on the roadmap.

Verification: [FacepalmRunnerTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/FacepalmRunnerTest.java) (file-size limit), [ScannerEngineTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/ScannerEngineTest.java) (thread-count validation and binary filtering), [SecretPatternBacktrackingTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/pattern/SecretPatternBacktrackingTest.java).

<a id="nfr-05"></a>

## NFR-05: Preserve component boundaries and wiring

Keep scanning independent of Maven APIs, use javax.inject annotations and index every extension component. Package-cycle exceptions are limited to those documented in the architecture tests.

Verification: [ArchitectureTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/ArchitectureTest.java), [ComponentWiringTest](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/ComponentWiringTest.java).

Compatibility evidence also comes from the [CI matrix](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/.github/workflows/build.yaml).
Performance measurements are described in the [benchmark guide](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/CONTRIBUTING.md#benchmarks).
There is no claim of a fixed scan time or exhaustive secret detection.
