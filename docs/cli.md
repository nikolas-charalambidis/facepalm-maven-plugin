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
java -jar facepalm-maven-plugin-<version>-cli.jar [options] [<directory>]
```

`<directory>` is the directory to scan (default: the current one). `--help` lists every option with its default,
`--version` prints the version.

## Options

The options are the parameters of the `scan` goal (see [Configuration](configuration.md)) under kebab-case names, with
the same defaults and the same meaning. A configuration gives the same findings and the same pass or fail on the command
line as in Maven.

| Option | Maven parameter | Default |
|---|---|---|
| `--output <dir>` | (`project.build.directory`) | `<directory>/target`: where `facepalm-findings.json` and `facepalm-statistics.json` are written |
| `--threads <n>` | `threads` | the number of CPUs |
| `--max-file-size-bytes <bytes>` | `maxFileSizeBytes` | `5242880` |
| `--skip-binary-regex <regex>` | `skipBinaryRegex` | `.*\.(png\|jpg\|jpeg\|gif\|pdf\|zip\|jar\|class\|tar\|gz\|exe\|dll)$` |
| `--skip-dirs <dir,...>` | `skipDirs` | the default skip directories |
| `--use-default-skip-dirs[=<true\|false>]` | `useDefaultSkipDirs` | not set: `--skip-dirs` replaces the defaults, with a deprecation warning |
| `--suppressions[=<true\|false>]` | `facepalm.suppressions` | `true` |
| `--scoring-strategy <strategy>` | `scoring.strategy` | `WEIGHTED_QUADRATIC` |
| `--error-threshold <score>` | `errorThreshold` | `80` |
| `--warning-threshold <score>` | `warningThreshold` | `40` |
| `--fail-on-error[=<true\|false>]` | `failOnError` | `true` |
| `--fail-on-warnings[=<true\|false>]` | `failOnWarnings` | `false` |
| `--show-scoring[=<true\|false>]` | `showScoring` | `false` |
| `--show-details[=<true\|false>]` | `showDetails` | `false` |
| `--show-processed[=<true\|false>]` | `showProcessed` | `false` |
| `--show-skipped[=<true\|false>]` | `showSkipped` | `false` |
| `--debug` | (`mvn -X`) | off: log at DEBUG level, list processed and skipped files, and print stack traces |

- **Booleans:** `--fail-on-warnings` alone means `true`; turn an option off with `=false`, such as
  `--fail-on-error=false`.
- **Lists:** `--skip-dirs` is comma-separated like `-DskipDirs=a,b` in Maven: values are stripped, and blank and
  duplicate values are dropped. For example, `--skip-dirs logs,tmp --use-default-skip-dirs` adds two directories to
  the defaults.
- **Not on the command line:** the nested configuration of the goal (`evaluators`, `postProcessing`); the CLI uses its
  defaults.

The log looks like the Maven goal's: every ERROR and WARNING finding with its file, line and masked secret.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | No finding fails the scan. Findings below the thresholds, or not failing under `--fail-on-error` / `--fail-on-warnings`, are logged only. |
| `1` | Findings fail the scan, by the same rules as `failOnError` and `failOnWarnings` in Maven. |
| `2` | Invalid arguments (the usage is printed), or the scan could not run, for example because of an invalid `--skip-binary-regex` or an unwritable output directory. Only `--debug` prints the stack trace. |

## Running from an IDE

`dev.nichar.facepalm.FacepalmCLI` is a plain main class. Import the project as a Maven project into IntelliJ IDEA,
Eclipse or VS Code (with the Java extension), and run `FacepalmCLI` with program arguments such as
`--output /tmp/facepalm /path/to/project`. The default module classpath is enough; `provided` dependencies are not
needed.

The version comes from `facepalm-version.properties`, which Maven filters when it processes the resources. The IDEs do
this when they build an imported Maven project; if the resources were not processed, the version shows as `unknown`.

In code and tests, call `FacepalmCLI.run(args)`: it returns the exit code without stopping the JVM, unlike `main`.

## See also

- [Git Hooks](git-hooks.md#without-maven) - a pre-commit hook that runs the jar.
- [Configuration](configuration.md) - what each parameter does.
