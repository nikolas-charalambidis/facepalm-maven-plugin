# Contributing to Facepalm Maven Plugin

Thanks for taking the time to contribute. Facepalm is a small, focused security tool — the bar for changes is "does this make the guardrail safer, faster, or quieter, without adding noise?"

## Table of contents

- [Code of conduct](#code-of-conduct)
- [Ways to contribute](#ways-to-contribute)
- [Reporting bugs](#reporting-bugs)
- [Proposing features](#proposing-features)
- [Development setup](#development-setup)
- [Building and testing](#building-and-testing)
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

1. Search existing issues — many false positives are already known and tracked in `docs/faq.md`.
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

The first build runs unit tests, integration tests (which spawn real Maven processes via `itf-jupiter-extension`), Spotless, and PMD/CPD. Expect 1–3 minutes on a modern laptop.

### IDE setup

- **IntelliJ IDEA**: import as a Maven project. Enable annotation processing (for Lombok). Install the *Lombok* plugin if it is not bundled. Set the Java formatter to use `eclipse.xml` (Eclipse formatter plugin) so your edits match Spotless.
- **VS Code**: install the *Extension Pack for Java* and *Lombok Annotations Support*.

## Building and testing

| Task | Command |
|------|---------|
| Full build (tests + quality gates) | `mvn clean verify` |
| Unit tests only | `mvn test` |
| Integration tests only | `mvn verify -DskipUnitTests` (or `mvn failsafe:integration-test failsafe:verify`) |
| Apply formatting | `mvn spotless:apply` |
| Check formatting | `mvn spotless:check` |
| Run the plugin against this repo | `mvn facepalm:scan` |
| Generate the site (Javadoc, PMD, JaCoCo, Surefire) | `mvn site` |

Integration test fixtures live under `src/test/resources-its/dev/nichar/facepalm/FacepalmMojoIT/` and are filtered so `${project.version}` resolves to your local build. Add a new directory there to cover a new scenario, and wire it from `FacepalmMojoIT`.

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
2. A **unit test** in `src/test/java/dev/nichar/facepalm/pattern/SecretPatternRegistryTest.java` covering positive and negative cases.
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

PRs are merged with **Squash and merge** (except the bot's forward PRs, which use **Rebase and merge**), so GitHub turns the PR title into the commit subject and appends the PR number, e.g. `fix: compute multi-line line numbers via binary search (#39)`. The PR then links to the issue. `Fixes #NN` only auto-closes issues for PRs into `master`; for PRs into `release/X.Y.x`, the bot copies it into the forward PR, so the issue closes when that PR is merged into `master`.

Pull request checklist:

- [ ] `mvn clean verify` passes locally.
- [ ] Formatting applied (`mvn spotless:apply`).
- [ ] New or changed Mojo parameters documented in `docs/configuration.md`.
- [ ] New detection patterns have unit and integration tests.
- [ ] No real secrets committed (the plugin will catch you, but please don't make it have to).
- [ ] PR description explains the *why*, not just the *what*.

PRs target `master` for new features. Fixes for a released line target its `release/X.Y.x` branch; they are forwarded to `master` automatically (see below). Keep PRs focused — one concern per PR.

## Branches and versioning

- `master` — next minor/major release.
- `release/X.Y.x` — maintenance line for `X.Y.*` patch releases (e.g. `release/1.0.x`).
- `feature/<name>`, `bugfix/<issue>/<name>` — short-lived work branches.

`pom.xml` never contains a real version. It uses the Maven [CI-friendly](https://maven.apache.org/maven-ci-friendly.html) placeholder `<version>${revision}</version>`, which defaults to `0.0.0-SNAPSHOT` for local builds. `flatten-maven-plugin` writes the resolved version into the installed/deployed POM. Because `pom.xml` is identical on every branch, commits move between branches without version conflicts.

`master` requires linear history (merge commits are rejected), so fixes are forward-ported by cherry-picking with `-x`, which records the source commit. This is automated by `.github/workflows/forward.yaml`:

- Every PR merged into `release/X.Y.x` gets its own forward PR into `master`: its commits are cherry-picked onto `forward/<pr>-to-master`, the PR is titled `[Forward master] <original title>`, links the source PR, and carries over its `Fixes #NN` lines.
- Commits pushed directly (without a PR) are forwarded as one PR per push, without closing keywords.
- Commits already on `master` are skipped. Add `[no-forward]` to a commit message to keep a release-only change off `master`.
- On conflict, the commit is pushed with conflict markers and the PR becomes a draft; the PR description lists the commands to resolve it.
- The maintainer merges the forward PR with **Rebase and merge**.

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
