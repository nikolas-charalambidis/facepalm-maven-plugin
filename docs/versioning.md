# Versioning

Facepalm follows [Semantic Versioning](https://semver.org) strictly. A version number `X.Y.Z` tells you what an
upgrade can change in your build:

- **Patch** (`1.1.0` to `1.1.1`): bug fixes and security fixes only. Upgrade without reading anything.
- **Minor** (`1.1.x` to `1.2.0`): new features that you have to turn on. Nothing changes until you do.
- **Major** (`1.x` to `2.0.0`): changes that can break a build that worked before. Read the release notes.

## The public contract

The contract is everything you configure or consume. Only a major release changes or removes any of it:

- **Goals and their parameters:** goal names, parameter names, `-D` properties, types, the documented defaults and
  what each parameter does ([Configuration](configuration.md)).
- **Detection and scoring:** the behavior documented in [Detection Patterns](patterns.md) and
  [Configuration](configuration.md): scoring strategies, thresholds, and what fails the build.
- **Pattern names:** they are the rule ids in the reports, so suppressions and dashboards can rely on them.
- **Output files and their fields:** `facepalm-findings.json`, `facepalm-statistics.json` and the site report
  ([Reporting](reporting.md)). New fields can appear in a minor release; existing fields are not renamed or removed.
- **The minimum Java and Maven versions:** Java 17 and Maven 3.6.3.

Behavior that is not documented is not part of the contract, and neither is the wording of log messages.

## What each level allows

| Change | Level | Example |
|---|---|---|
| Documentation, build and CI changes, tests, refactoring without a behavior change | any release | |
| Bug fix: the behavior now matches the documentation, even if a build that passed now fails | patch | 1.0.5: configured pattern overrides are rejected instead of scanning with no patterns ([#284](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/pull/284)) |
| Security fix, dependency update without a contract change | patch | |
| New opt-in feature: a parameter whose default keeps the old behavior, a new goal, a new output file or JSON field, a new detection pattern | minor | 1.1.0: `useDefaultSkipDirs=true` lets `skipDirs` add to the default skip directories; when it is not set, `skipDirs` still replaces them as before, with a deprecation warning ([#258](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/258)) |
| Changed default or meaning of a parameter, removed or renamed parameter, property, goal, pattern name or output field, raised minimum Java or Maven version | major | Planned for 2.0.0: `skipDirs` adds to the defaults when `useDefaultSkipDirs` is not set ([#259](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/259)) |

A breaking change is staged: the new behavior first comes as an opt-in in a minor release, with a deprecation warning
for the old behavior, so you can switch early and on your own schedule. The next major release then makes it the
default. `skipDirs` above is the example: opt-in with `useDefaultSkipDirs=true` in 1.1.0, the default planned for 2.0.0.

## Release lines

- `master` holds the next minor or major release.
- `release/X.Y.x` holds the patch releases of `X.Y`, for example `release/1.1.x` for 1.1.1, 1.1.2, and so on.
- Once the next minor is released, the older line gets only security fixes. For example, `release/1.0.x` takes only
  security fixes since 1.1.0.
- A fix lands on the oldest affected line and is forwarded automatically to every newer line and to `master`, so a
  fix released in 1.1.2 is also in 1.2.0.

How releases are built and checked is described in [CONTRIBUTING.md](../CONTRIBUTING.md#branches-and-versioning).
