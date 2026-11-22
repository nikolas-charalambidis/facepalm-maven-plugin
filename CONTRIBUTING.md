# Contributing to Facepalm Maven Plugin

Thanks for taking the time to contribute. Facepalm is a small, focused security tool — the bar for changes is "does this make the guardrail safer, faster, or quieter, without adding noise?"

## Table of contents

- [Code of conduct](#code-of-conduct)
- [Ways to contribute](#ways-to-contribute)
- [Reporting bugs](#reporting-bugs)
- [Proposing features](#proposing-features)
- [Development setup](#development-setup)
- [Building and testing](#building-and-testing)
- [Benchmarks](#benchmarks)
- [Code style](#code-style)
- [Adding detection patterns](#adding-detection-patterns)
- [Commit and PR conventions](#commit-and-pr-conventions)
- [Release process](#release-process)
- [License](#license)

## Code of conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

## Ways to contribute

- **Report false positives or false negatives** — these are the most valuable bug reports for a secret scanner.
- **Add or refine detection patterns** for new providers and credential formats.
- **Improve the scoring engine** (evaluators, strategies, post-processors).
- **Tighten the docs** under `docs/`.
- **Fix bugs** filed in [Issues](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues).

## Reporting bugs

Before opening an issue, please:

1. Search existing issues — many false positives are already reported there. Ways to handle false positives are in [docs/faq.md](docs/faq.md#how-do-i-handle-false-positives).
2. Reproduce against the latest published version.
3. Open the issue with the matching [template](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/new/choose) (bug report, missed or wrong detection, feature request) and include:
   - Plugin version and Maven version (`mvn -v`).
   - JDK version (Java 17+ is required).
   - A **minimal reproducer**: ideally a snippet of the offending file (sanitized) and the `pom.xml` configuration.
   - The full `mvn -X facepalm:scan` output for scoring context, or `-DshowScoring=true -DshowDetails=true`.

For suspected secret-detection misses, please **do not paste real secrets** — replace them with fake values that share the same structural shape.

Security vulnerabilities in the plugin itself must not be reported as public issues; see [SECURITY.md](SECURITY.md).

## Proposing features

Open an issue describing the use case before sending a PR for non-trivial changes. Good proposals answer:

- What problem does it solve? Who hits it?
- Does it belong in the core plugin, or as configuration?
- What is the impact on scan performance and the noise/false-positive rate?

The project deliberately stays minimal: it is a guardrail, not a reporting suite.

## Development setup

Requirements:

- JDK 17 (Temurin recommended — matches CI).
- Maven 3.9+.
- Git.

Clone and bootstrap:

```bash
git clone https://github.com/nikolas-charalambidis/facepalm-maven-plugin.git
cd facepalm-maven-plugin
mvn clean verify
```

The first build runs unit tests, integration tests (which spawn real Maven processes via `itf-jupiter-extension`), Spotless, PMD/CPD, and a Facepalm scan of the repository. Expect 1–3 minutes on a modern laptop.

### Git hooks

This repository scans itself with the latest released Facepalm, on every commit and every CI build. Enable the committed hooks once per clone:

```bash
git config core.hooksPath .githooks
```

- `.githooks/pre-commit` and `.githooks/pre-push` run `mvn -B -ntp facepalm:scan` and block the commit or push on a finding, as described in [docs/git-hooks.md](docs/git-hooks.md).
- The `verify` phase runs the same scan, so CI fails on a leaked secret too.
- Intentional fake secrets are excluded narrowly: `pom.xml` skips the IT fixtures under `src/test/resources-its`, and `.facepalmignore` lists the unit tests with sample keys. Add a new test file with fake secrets to `.facepalmignore`, or use an inline `facepalm:ignore` marker with a reason.

### IDE setup

- **IntelliJ IDEA**: import as a Maven project. Enable annotation processing (for Lombok). Install the *Lombok* plugin if it is not bundled. Set the Java formatter to use `eclipse.xml` (Eclipse formatter plugin) so your edits match Spotless.
- **VS Code**: install the *Extension Pack for Java* and *Lombok Annotations Support*.

## Building and testing

| Task | Command |
|------|---------|
| Full build (tests + quality gates) | `mvn clean verify` |
| Unit tests only | `mvn test` |
| Integration tests only | `mvn verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| Apply formatting | `mvn spotless:apply` |
| Check formatting | `mvn spotless:check` |
| Run the plugin against this repo | `mvn facepalm:scan` |
| Generate the site (Javadoc, PMD, JaCoCo, Surefire) | `mvn site` |
| Run the JMH benchmarks (about 2 minutes, see [Benchmarks](#benchmarks)) | `mvn -P bench verify -DskipTests` |
| Fuzz one target for a minute (see [Fuzzing](#fuzzing)) | `JAZZER_FUZZ=1 mvn test -Dtest=RegexSecretExtractorFuzzTest` |

Integration test fixtures live under `src/test/resources-its/dev/nichar/facepalm/FacepalmMojoIT/` and are filtered so `${project.version}` resolves to your local build. Add a new directory there to cover a new scenario, and wire it from `FacepalmMojoIT`.

`mvn verify` fails when unit-test coverage drops below 80% of lines or 80% of branches (`jacoco:check` on the whole bundle). Only unit tests count: the integration tests run the plugin in forked Maven builds, which JaCoCo does not see. A build without unit tests (`-DskipTests`) skips the check.

On every pull request, the `CI / Verify & Build Site` job summary shows the overall coverage and the coverage of the lines the PR adds or changes under `src/main/java`, and annotates the uncovered ones in the diff. Below 80% of changed lines it warns, but does not fail. Run it locally after `mvn verify site` with `python3 .github/scripts/changed-line-coverage.py target/site/jacoco/jacoco.xml "$(git merge-base origin/master HEAD)"` (use your PR's base branch).

### Fuzzing

[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz tests feed random input to the code that reads arbitrary file content: `TextDecoderFuzzTest` (random bytes) and `RegexSecretExtractorFuzzTest` (every default pattern over random text). They find crashes and catastrophic backtracking that unit tests miss.

- **Every build:** `mvn test` runs each `@FuzzTest` once, in regression mode, with the inputs under `src/test/resources/<package>/<Class>Inputs/` (a crash found while fuzzing is saved there, so it stays a regression test).
- **Locally:** `JAZZER_FUZZ=1 mvn test -Dtest=<Class>` fuzzes one target for a minute. The corpus goes to `.cifuzz-corpus/`, which is ignored by Git.
- **CI:** [ClusterFuzzLite](https://google.github.io/clusterfuzzlite/) fuzzes the targets for 2 minutes in total on pull requests that change code (`cflite_pr.yaml`) and for 30 minutes weekly (`cflite_batch.yaml`). An input that takes longer than 10 seconds counts as a timeout. Neither workflow is a required check.
- **New target:** name the class `*FuzzTest` and give it a `public static void fuzzerTestOneInput(FuzzedDataProvider)` method for ClusterFuzzLite, called from the `@FuzzTest` method (`.clusterfuzzlite/build.sh` picks it up).

## Benchmarks

JMH microbenchmarks measure how fast the engine's hot paths are and how much memory they allocate. They are measurements, not tests: they have no expected values and no thresholds, so a slower or more allocating change does not fail them. You find a regression by comparing results.

### How they work

- The benchmarks live under `src/test/java/dev/nichar/facepalm/bench` and compile only in the `bench` profile. The default build excludes them from test compilation and has no JMH dependency, so `mvn verify` and the required `CI / Verify & Build Site` check neither compile nor run them.
- In the profile, the JMH annotation processor generates the benchmark harness at test compilation. In the `verify` phase, after the tests, `exec-maven-plugin` starts `org.openjdk.jmh.Main` in a new JVM on the test classpath.
- Each benchmark runs in its own forked JVM: 3 warmup iterations of 1 s, whose results are discarded while the JIT compiles the code, then 5 measured iterations of 1 s. The whole suite takes about 2 minutes.
- The input is deterministic: realistic lines (Java, properties, YAML, JSON, XML, logs, a long minified line), plus secrets generated at runtime from a fixed seed. Every run measures the same input, and the sources contain no literal secrets. Components come from the real dependency injection wiring, with a log that discards everything.

| Benchmark | Measures |
|---|---|
| `PatternBenchmark.allPatternsOnAllLines` | Every single-line built-in pattern on every corpus line: the raw cost of the regexes. |
| `ExtractorBenchmark.extract` | `RegexSecretExtractor` on a 200-line file: matching, snippet masking, deduplication. |
| `EvaluatorBenchmark.evaluate` | Each evaluator (one result per `evaluator` parameter) on the findings of a 200-line file. |
| `ScannerEngineBenchmark.scan` | A full scan of a generated project of 200 files x 50 lines on one thread: discovery, `.gitignore`, reading, extraction, evaluation, post-processing. |

### Reading the results

The console prints a table, and `target/jmh-result.json` holds the same data. For each benchmark:

- `Score ± Error`: average time per operation (`avgt`) with its 99.9 % confidence interval. Times depend on the machine and fluctuate on shared CI runners, so compare them only between runs on the same machine.
- `gc.alloc.rate.norm`: bytes allocated per operation. It hardly depends on the machine or its load, so compare it first: a change that raises it allocates more on every run.
- `gc.alloc.rate`, `gc.count`, `gc.time`: allocation rate and garbage collections during the measurement; useful as context only.

To compare two runs, open both JSON files in a JMH visualizer such as [jmh.morethan.io](https://jmh.morethan.io), or compare the tables. Treat a time difference inside the error margins as noise.

### When they fail

The run fails, and with it `mvn -P bench verify` and the `Benchmarks` workflow, when:

- the benchmark sources do not compile, for example after an API change in `src/main` that they use;
- a benchmark throws, in setup or in the measured method. JMH runs with `-foe true` (fail on error), so the error fails the build instead of skipping the benchmark. The setup checks its own assumptions too: `EvaluatorBenchmark` fails if an evaluator is renamed or removed (update its `@Param` list) or if the corpus produces no findings;
- without `-DskipTests`: a unit or integration test or a quality gate fails, because the benchmarks run only after them;
- in the workflow only: no `target/jmh-result.json` is written (the upload step requires it), or the job exceeds its 30-minute timeout.

They do **not** fail when a change is slower or allocates more. The workflow is not a required check, and nothing compares results automatically.

### When they run

- Locally, on demand: `mvn -P bench verify -DskipTests`, or a subset with `-Dbench.include=<regex>`, for example `-Dbench.include=ScannerEngineBenchmark`.
- The `Benchmarks` workflow (`.github/workflows/bench.yaml`): weekly on Monday, on manual dispatch, and on pull requests labelled `performance` (when the label is added and on every later push). It uploads `jmh-result.json` as the `jmh-result` artifact. For a performance change, compare that artifact with the one from the latest weekly run on `master`.

## Code style

All of the following are enforced by `mvn verify`:

- **Spotless** with the Eclipse formatter (`eclipse.xml`). Run `mvn spotless:apply` before committing.
- **License header** on every Java file (the Spotless config injects it from `license.header`):
  ```java
  /*
   * SPDX-License-Identifier: Apache-2.0
   * Copyright 2026 Nikolas Charalambidis
   */
  ```
- **PMD** with `pmd-ruleset.xml`. The build fails at priority ≤ 2.
- **CPD** at `minimumTokens=100`. Refactor duplicated logic instead of suppressing.

Coding conventions:

- Use `Locale.ROOT` in **every** `String.format`, `toLowerCase` and `toUpperCase` call (enforced by PMD). Locale-sensitive formatting is a known bug class in this project.
- Use `javax.inject.@Inject` / `@Named` — **not** `jakarta.inject`. The Maven Sisu container only sees the JSR-330 annotations.
- Prefer Lombok (`@RequiredArgsConstructor`, `@Getter`, `@Value`) over hand-written boilerplate, but match the surrounding file's style.
- Comma-separated CLI parameters must flow through `CommaSeparatedConfigurator` so `-DskipDirs=a,b` continues to work.
- Public Mojo parameters belong in `docs/configuration.md`. Keep that table in sync.
- Every static field (final or not) has a Javadoc comment describing its purpose.

## Adding detection patterns

A new pattern usually means:

1. A `SecretPattern` registered in `SecretPatternRegistry`.
2. A **unit test** in `src/test/java/dev/nichar/facepalm/pattern/SecretPatternRegistryTest.java` covering positive and negative cases, and a positive and a negative sample in `SecretPatternSamplesTest`. The samples also feed `SecretPatternBacktrackingTest`, which fails when the pattern does not match in linear time (see [Linear-time matching](docs/patterns.md#linear-time-matching)).
3. An **integration test fixture** in `src/test/resources-its/.../dirty_project/` (or a new fixture) demonstrating a real-file detection.
4. A short note in `docs/patterns.md`.

Aim for patterns that are **specific** (provider prefix, fixed length, charset) rather than broad. Broad patterns drive false positives, which are the single most common reason users disable security tooling.

## Commit and PR conventions

Commit messages and PR titles follow [Conventional Commits](https://www.conventionalcommits.org):

- `fix: …` — bug fix
- `feat: …` — new feature
- `perf: …` — performance improvement without behavior change
- `refactor: …` — code change without behavior change
- `test: …` — tests only
- `docs: …` — documentation only
- `chore: …` — build, dependencies, housekeeping
- `ci: …` — GitHub Actions workflows and release machinery

Keep the subject imperative, lowercase after the prefix, and under ~72 characters. Do not put issue numbers in the subject.

Issues are linked from the **PR description**, not the commit, with each closing keyword on its own line (the forward-port bot copies only such lines):

```
Fixes #14
```

PRs are merged with **Squash and merge** (except the bot's forward PRs, which use **Rebase and merge**), so GitHub turns the PR title into the commit subject and appends the PR number, e.g. `fix: compute multi-line line numbers via binary search (#39)`. The PR then links to the issue. `Fixes #NN` only auto-closes issues for PRs into `master`; for PRs into `release/X.Y.x`, the bot copies it into each forward PR, so the issue closes when the last one is merged into `master`.

Pull request checklist:

- [ ] `mvn clean verify` passes locally.
- [ ] Formatting applied (`mvn spotless:apply`).
- [ ] New or changed Mojo parameters documented in `docs/configuration.md`.
- [ ] New detection patterns have unit and integration tests.
- [ ] No real secrets committed (the plugin will catch you, but please don't make it have to).
- [ ] PR description explains the *why*, not just the *what*.

PRs target `master` for new features. Fixes for a released line target its `release/X.Y.x` branch; they are forwarded to every newer line and `master` automatically (see below). Keep PRs focused — one concern per PR.

## Branches and versioning

- `master` — next minor/major release.
- `release/X.Y.x` — maintenance line for `X.Y.*` patch releases (e.g. `release/1.0.x`).
- `feature/<name>`, `bugfix/<issue>/<name>` — short-lived work branches.

`pom.xml` never contains a real version. It uses the Maven [CI-friendly](https://maven.apache.org/maven-ci-friendly.html) placeholder `<version>${revision}</version>`, which defaults to `0.0.0-SNAPSHOT` for local builds. `flatten-maven-plugin` writes the resolved version into the installed/deployed POM. Because `pom.xml` is identical on every branch, commits move between branches without version conflicts.

`master` requires linear history (merge commits are rejected), so fixes are forward-ported by cherry-picking with `-x`, which records the source commit. This is automated by `.github/workflows/forward.yaml`:

- Forwarding chains one line at a time: each `release/X.Y.x` forwards to the next higher existing `release/X.Y.x`, or to `master` when there is none (e.g. `release/1.0.x` → `release/1.1.x` → `master`). Merging a forward PR into a release branch forwards it to the next line.
- Every PR merged into `release/X.Y.x` gets its own forward PR into the next line: its commits are cherry-picked onto `forward/<pr>-to-<line>` (e.g. `forward/42-to-1.1.x`, `forward/43-to-master`), the PR is titled `[Forward <target>] <original title>`, links the source PR, and carries over its `Fixes #NN` lines.
- Commits pushed directly (without a PR) are forwarded as one PR per push, without closing keywords.
- Commits already on the target are skipped. Add `[no-forward]` to a commit message to keep a release-only change off all newer lines.
- On conflict, the commit is pushed with conflict markers and the PR becomes a draft; the PR description lists the commands to resolve it.
- The maintainer merges the forward PR with **Rebase and merge**.

### Branch protection

`master` and `release/**` are protected by repository rulesets that apply to everyone, including administrators:

- Changes only via pull request, merged with **Squash and merge** or **Rebase and merge**; the `CI / Verify & Build Site` and `Workflow Lint` checks must pass.
- Linear history; no force pushes or branch deletions.
- Approvals are dismissed when new commits are pushed.
- No approving review is required: the project has a single maintainer, so a required review could never be given. This is a deliberate trade-off (OpenSSF Scorecard's Branch-Protection check cannot reach its maximum).

Release tags `v*` cannot be moved or deleted.

**Contributors never forward-port.** Open your PR against the oldest affected branch; forwarding is the maintainer's job.

The released version exists only as a git tag `vX.Y.Z`. To build a specific version locally, pass it explicitly: `mvn install -Drevision=1.2.3`.

## Release process

Releases are fully automated via `.github/workflows/deploy.yaml` (manual `workflow_dispatch`):

1. Maintainer triggers the workflow, selects the branch to release (`master` or `release/X.Y.x`) in "Use workflow from", and picks `patch` / `minor` / `major`.
2. CI finds the latest `vX.Y.Z` tag reachable from that branch and bumps it (e.g. `v1.0.1` + `patch` → `1.0.2`).
3. CI runs `mvn -P release clean verify -Dgpg.skip -Drevision=<version>` without any secrets (all tests and quality gates, sources and javadoc jars). Only then are the Central credentials and the GPG key installed, and `mvn -P release,central deploy -DskipTests` signs everything and uploads the bundle to the [Maven Central Portal](https://central.sonatype.com). Maven Central is the only repository; the GitHub Release attaches the same signed files. The site is published by `build.yaml`, not by the release.
4. CI tags the built commit `v<version>`, pushes only the tag (no commits are pushed back), and creates a GitHub Release with notes generated from the merged PRs since the previous tag and the signed artifacts attached.
5. While `autoPublish` is `false` in the `central` profile, the upload stops as *Validated* in the Portal under **Deployments**; the maintainer reviews it and clicks **Publish**. Maven Central releases are permanent — they can never be deleted or replaced.
6. If the release is the highest version, facepalm-bot opens a PR into `master` (`docs: use version <version>`) that updates the plugin version in the `README.md` and `docs/*.md` install snippets. Merge it only after the deployment is published in step 5.

Guards: releases run only from `master` (`minor` or `major`) and `release/X.Y.x` (`patch`, `X.Y.*` versions only); any other branch is rejected. An existing tag aborts the release, and only one release runs at a time.

Do not put a version in `pom.xml` — the version is derived from tags.

Each release has SLSA build provenance, attached to the GitHub Release as `*.intoto.jsonl` and stored as a GitHub attestation. Verify a downloaded jar with `gh attestation verify facepalm-maven-plugin-<version>.jar --repo nikolas-charalambidis/facepalm-maven-plugin`.

Releases are reproducible: rebuilding a tag with `-Drevision=<version>` and the `project.build.outputTimestamp` from its GitHub Release notes yields byte-identical jars, provided you use the exact JDK named there (the javadoc jar bundles JDK-specific resources).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License, Version 2.0](LICENSE), the same license as the project. Every Java file must carry the SPDX header shown above.

The repository is [REUSE](https://reuse.software) compliant, checked by the `REUSE Compliance` job in CI:

- Java files declare their license in the SPDX header. Every other file gets it from `REUSE.toml`, which covers the whole repository with Apache-2.0.
- Adding a file you did not write (copied or adapted from elsewhere) needs its own entry in `REUSE.toml` with the original copyright and license, and the license text in `LICENSES/` (`reuse download <SPDX-id>`). `CODE_OF_CONDUCT.md` (CC-BY-4.0) is the example.
- Run `reuse lint` locally (`pipx install reuse`) to check.

Third-party licenses are checked by `mvn verify` (`license-maven-plugin`, `add-third-party`):

- Every runtime dependency must have at least one license on the allow-list in `pom.xml`: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, EPL-2.0 or Public-Domain. Test and provided dependencies are not checked. A dependency with a license outside the list fails the build.
- License names from dependency POMs are mapped to these names in `src/license/license-merges.txt`. Add a line there when a new dependency spells a known license differently.
- A dependency whose POM declares no license also fails the build. After checking its source, add it to `src/license/THIRD-PARTY.properties`.
- The list is packaged in the jar as `THIRD-PARTY.txt`, and the site shows it under Project Reports, Third Parties.
