# Claude Code Guide — Facepalm Maven Plugin

A Maven plugin (`dev.nichar:facepalm-maven-plugin`) that scans repositories for leaked secrets (API keys, tokens, credentials) and blocks commits before they reach Git. The primary goal is a hard stop, not a report.

- Packaging: `maven-plugin`
- Java: 17 (source & target)
- Default goal: `facepalm:scan` (bound to the `verify` phase)
- DI: Guice + Eclipse Sisu (JSR-330 `javax.inject` — NOT `jakarta.inject`)
- Templating: FreeMarker (reports under `src/main/resources/templates`)
- JSON: Jackson Databind

## Repository layout

```
src/main/java/dev/nichar/facepalm/
  FacepalmMojo.java          # @Mojo(name="scan") — main entry point
  FacepalmReportMojo.java    # Maven site report integration
  FacepalmCLI.java           # PSVM entry point for running outside Maven
  FacepalmConfig.java
  config/                    # *Config records for engine wiring
  configurator/              # Custom Plexus configurator for comma-separated input
  engine/                    # ScannerEngine, FacepalmRunner, Finding, Severity
  engine/evaluator/          # FindingEvaluator implementations (entropy, location, ...)
  engine/postprocessor/      # Score post-processing (deduplication, noise reduction)
  engine/extractor/          # SecretExtractor
  module/                    # Guice modules (FacepalmConfigModule, FacepalmLogModule)
  pattern/                   # SecretPattern, SecretPatternRegistry
  report/                    # Reporter, FindingReport, FreeMarker rendering
src/main/resources/templates # FreeMarker templates for reports
src/test/java                # Unit tests (JUnit 5)
src/test/resources-its       # Integration test projects driven by itf-jupiter-extension
docs/                        # User-facing docs (configuration, git-hooks, patterns, faq, reporting)
pom.xml, pmd-ruleset.xml, eclipse.xml, license.header
```

## Common commands

| Task | Command |
|------|---------|
| Full build + ITs + quality gates | `mvn clean verify` |
| Apply formatting (Spotless / Eclipse style) | `mvn spotless:apply` |
| Check formatting only | `mvn spotless:check` |
| Run unit tests | `mvn test` |
| Run integration tests only | `mvn failsafe:integration-test failsafe:verify` |
| Generate site (Javadoc, PMD, JaCoCo, Surefire) | `mvn site` |
| Run the plugin against the current project | `mvn facepalm:scan` |

Do **not** invoke `mvn deploy` or `mvn release:*` locally — releases are driven by `.github/workflows/deploy.yaml`.

## Code style and quality gates

The `verify` phase enforces **all** of these. A change is not done until `mvn clean verify` is green.

- **Spotless** with Eclipse formatter (`eclipse.xml`) and the license header in `license.header`. Run `mvn spotless:apply` before committing.
- **License header** on every Java file:
  ```java
  /*
   * SPDX-License-Identifier: Apache-2.0
   * Copyright 2026 Nikolas Charalambidis
   */
  ```
- **PMD** with `pmd-ruleset.xml`. Build fails at priority ≤ 2.
- **CPD** (copy-paste) at `minimumTokens=100`, identifiers ignored.
- **JaCoCo** is wired in; keep coverage from regressing.

## Conventions specific to this codebase

- **Use `Locale.ROOT` in every `String.format`, `toLowerCase` and `toUpperCase` call.** This was a real bug fix (see `233c435`); do not regress. PMD enforces it (`UseLocaleWithStringFormat`, `UseLocaleWithCaseConversions`).
- **JSR-330 only.** Use `javax.inject.@Inject` / `@Named`. Switching to `jakarta.inject` will silently break Sisu wiring.
- **Lombok** is on the classpath. Prefer `@RequiredArgsConstructor`, `@Getter`, `@Value` for records-of-records, but stay consistent with surrounding files.
- **Guava** is declared explicitly in `pom.xml` (and excluded from Guice) to avoid a `Stopwatch` `ClassNotFoundException` from Maven's bundled Guava. Keep the dependency; updating its version is fine, but verify `facepalm:scan` and `facepalm:report` on the oldest supported Maven (3.6.3) as well as the current one.
- **Comma-separated parameters** (e.g. `skipDirs`) flow through `CommaSeparatedConfigurator` so CLI overrides like `-DskipDirs=a,b` work. Wire new collection-valued `@Parameter`s through it.
- **Severity, scoring strategies, and thresholds** are defined in `engine/` — see `docs/configuration.md` before changing defaults; they are part of the public contract.
- **Every static field (final or not) has a Javadoc comment** describing its purpose.

## Testing approach

- Unit tests: JUnit 5, in `src/test/java`.
- Integration tests: `itf-jupiter-extension` spawns real Maven builds against fixture projects under `src/test/resources-its/dev/nichar/facepalm/FacepalmMojoIT/` (`clean_project`, `dirty_project`, `dirty_project_fail_on_warnings`). Fixtures are filtered so `${project.version}` resolves to the version under test — keep `<filtering>true</filtering>` intact.
- When adding a new detection pattern or evaluator, add **both** a unit test in `pattern/` or `engine/` and at least one fixture case under `resources-its`.

## Branching and release

- Default branch: `master` (next minor/major). Maintenance lines live on `release/X.Y.x` (patch releases only). Work branches: `feature/<name>`, `bugfix/<issue>/<name>`.
- `pom.xml` uses the CI-friendly placeholder `<version>${revision}</version>` (default `0.0.0-SNAPSHOT`); `flatten-maven-plugin` resolves it in the installed/deployed POM. Never write a concrete version into `pom.xml` — it would reintroduce version conflicts between branches.
- `master` rejects merge commits (GitHub rule: linear history). Forwarding from `release/X.Y.x` to `master` is automated by `forward.yaml` (GitHub App token, secrets `FORWARD_APP_ID` / `FORWARD_APP_PRIVATE_KEY`): for every PR merged into the release branch it cherry-picks with `-x` onto `forward/<pr>-to-master` and opens a `[Forward master] <title>` PR carrying the source PR's `Fixes #N` lines (issues close when it merges into `master`); merge it with Rebase and merge. Never `git merge` into `master`. `[no-forward]` in a commit message skips forwarding.
- The release workflow (`deploy.yaml`, manual dispatch on the chosen branch) derives the version from the latest reachable `vX.Y.Z` tag plus the bump type, runs `mvn -P release verify -Dgpg.skip` without secrets, then `mvn -P release,central deploy -DskipTests` (`release` profile: sources/javadoc jars + GPG signing; `central` profile: upload to Maven Central via `central-publishing-maven-plugin`; secrets `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`), pushes only the `v<version>` tag — no commits — and, for the highest version, has facepalm-bot open a `docs: use version <version>` PR into `master` for the README/docs snippets (merge after publishing in the Portal). Releases run only from `master` (minor/major) and `release/X.Y.x` (patch, `X.Y.*` only); other branches are rejected, and runs are serialized (`concurrency: release`).
- `build.yaml` runs on pushes/PRs to `master` and `release/**`; GitHub Pages site deploys only from `master`.
- `master` and `release/**` are protected by a ruleset: changes only via PR (squash or rebase), required check `CI / Verify & Build Site`, no force pushes or deletions. `v*` tags cannot be moved or deleted.

## What to avoid

- Adding new top-level Maven modules — this is intentionally a single-module plugin.
- Introducing reflective code without an annotation index entry (Sisu will not see it).
- Logging via `System.out.println` — use the injected `Log` / `FacepalmLogModule`.
- Bypassing `failOnError` / `failOnWarnings` semantics in `FacepalmMojo`. Their contract is documented in `docs/configuration.md`.
- Touching `eclipse.xml` or `pmd-ruleset.xml` to "make Spotless/PMD pass" — fix the code instead.

## Useful docs (read before changing related code)

- `docs/configuration.md` — every public parameter and default.
- `docs/patterns.md` — how detection patterns work and how to extend them.
- `docs/git-hooks.md` — the pre-commit / pre-push contract users rely on.
- `docs/reporting.md` — report formats and FreeMarker template wiring.
- `docs/faq.md` — known false-positive categories.
