# Command Line

Facepalm also runs without Maven, as a self-contained jar. It needs only a Java 17 (or newer) runtime, which is handy
for projects built with Gradle or npm, for Git hooks on machines without Maven, and for CI jobs that do not build the
project.

The jar is published with every release from 1.2.0 on, next to the plugin jar, with the classifier `cli`:
`dev.nichar:facepalm-maven-plugin:<version>:jar:cli`.

## Download

From Maven Central (replace `<version>` with 1.2.0 or later):

```bash
VERSION=<version>
curl -fLO "https://repo1.maven.org/maven2/dev/nichar/facepalm-maven-plugin/$VERSION/facepalm-maven-plugin-$VERSION-cli.jar"
```

The jar is also attached to each [GitHub Release](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/releases),
with its GPG signature (`.asc`) and build provenance (`.intoto.jsonl`). Check the provenance with:

```bash
gh attestation verify facepalm-maven-plugin-$VERSION-cli.jar --repo nikolas-charalambidis/facepalm-maven-plugin
```

With Maven at hand, `mvn dependency:copy -Dartifact=dev.nichar:facepalm-maven-plugin:$VERSION:jar:cli -DoutputDirectory=.`
fetches it too.

## Run

```bash
java -jar facepalm-maven-plugin-<version>-cli.jar [--debug] [--output <dir>] [<directory>]
```

- `<directory>`: directory to scan (default: the current one).
- `--output <dir>`: where `facepalm-findings.json` and `facepalm-statistics.json` are written (default:
  `<directory>/target`).
- `--debug`: log at DEBUG level and list processed and skipped files.

The scan uses the default configuration of the Maven goal: the same patterns, scoring and thresholds, the default
`skipDirs`, inline `facepalm:ignore` markers and the `.facepalmignore` file. Parameters such as `skipDirs` or
`failOnWarnings` cannot be changed on the command line.

The log looks like the Maven goal's: every ERROR and WARNING finding with its file, line and masked secret.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | No finding reached the error threshold. Warnings are logged but do not fail the run (the default `failOnWarnings=false`). |
| `1` | At least one finding reached the error threshold, the arguments are invalid, or the scan could not run. The cause is printed with a stack trace. |

## See also

- [Git Hooks](git-hooks.md#without-maven) - a pre-commit hook that runs the jar.
- [Configuration](configuration.md) - the defaults the CLI uses.
