# Configuration

This document provides a comprehensive list of all configuration parameters available for the **Facepalm Maven Plugin**.

## Core Parameters

These parameters control the basic execution of the scan.

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `root` | `root` | `${project.basedir}` | Root directory for scanning. |
| `threads` | `threads` | Available Processors | Concurrent threads for scanning. |
| `maxFileSizeBytes` | `maxFileSizeBytes` | `5242880` (5MB) | Maximum file size in bytes; larger files are ignored. |
| `skipBinaryRegex` | `skipBinaryRegex` | `.*\\.(png\|jpg\|jpeg\|gif\|pdf\|zip\|jar\|class\|tar\|gz\|exe\|dll)$` | Regex to identify binary files to skip. |
| `skipDirs` | `skipDirs` | `.git, .svn, .hg, .idea, .vscode, .mvn, .gradle, target, node_modules, dist, build, out, .next` | Directories to exclude from scanning (comma-separated via CLI). Matched against directory names inside the scanned project only; excluded directories are not entered. Dotfiles such as `.env` are scanned. **A configured value replaces the defaults** (repeat the ones you want to keep); an empty value (`-DskipDirs=`) uses the defaults. |
| `showProcessed` | `showProcessed` | `false` | Log every processed file at debug level. |
| `showSkipped` | `showSkipped` | `false` | Log every skipped file at debug level. |

## Scoring & Thresholds

Facepalm uses a scoring engine (0-100) to determine if a finding is critical.

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `scoringStrategy` | `scoring.strategy` | `WEIGHTED_QUADRATIC` | Strategy used to combine risk and confidence. Available values: `AVERAGE`, `GEOMETRIC`, `ROOT_MEAN_SQUARE`, `WEIGHTED_QUADRATIC`, `GATEKEEPER`. |
| `errorThreshold` | `errorThreshold` | `80` | Score threshold for high-risk findings (Critical). |
| `warningThreshold` | `warningThreshold` | `40` | Score threshold for moderate-risk findings (Warning). |
| `showScoring` | `showScoring` | `false` | Add the score breakdown (score, risk, confidence) to each logged finding. ERROR and WARNING findings are always logged with their file, line and masked secret. |
| `showDetails` | `showDetails` | `false` | Log discovery and exclusion statistics. |
| `failOnError` | `failOnError` | `true` | Fail the build if any finding reaches the error threshold. |
| `failOnWarnings` | `failOnWarnings` | `false` | Fail the build if any finding reaches the warning threshold. Critical findings count too, also with `failOnError=false`. |

## Advanced Configuration

### Heuristics (`<evaluators>`)

Refine how Facepalm evaluates risk and legitimacy.

| Sub-parameter | Default                                         | Description |
|---------------|-------------------------------------------------|-------------|
| `interpolationPatternRegex` | `.*(?:\$\{[^}]+}\|\{\{[^}]+}}).*\|%[A-Za-z_][A-Za-z0-9_]*%\|<[A-Za-z_][A-Za-z0-9_ .-]*>\|\[[A-Za-z_][A-Za-z0-9_ .-]*]` | Regex to detect placeholders. `${VAR}` and `{{ var }}` may appear anywhere in the value; `%VAR%`, `<your-key>` and `[your-key]` must be the whole value, so URL-encoded passwords are not suppressed. |
| `highRiskExtensions` | `.env, .properties, .yml, .yaml, .conf, .ini`   | Extensions that increase the risk score. |
| `lowRiskExtensions` | `.md, .txt, .csv, .log, .example, .sample`      | Extensions that decrease the risk score. |
| `dummyKeywords` | `dummy, your_api_key, insert_here, ...`         | Keywords indicating fake data. |
| `prodPathMarkers` | `src/main/, .env, config`                       | Path markers indicating production environments. Matched against the path relative to the scanned project (see below). |
| `testPathMarkers` | `test, mock, spec`                              | Path markers indicating test environments. Matched against the path relative to the scanned project (see below). |
| `prodContextMarkers` | `prod, live`                                    | Surrounding code keywords indicating production use. |
| `mockContextMarkers` | `example, dummy, fake, mock`                    | Surrounding code keywords indicating mock use. |

Path markers are matched against the path relative to the scanned project, so directories above it (for example a CI workspace named `test-env`) never affect scoring, and `/` and `\` separators behave the same:

- A marker made of letters and digits only (`test`, `mock`, `spec`, `config`) matches a whole word of a path segment, split at punctuation and camelCase boundaries, optionally plural: `src/test/`, `__tests__`, `FooTest.java` and `app.spec.ts` match, `latest` and `contest` do not.
- Any other marker (`src/main/`, `.env`) matches at the start of a path segment: `.env` matches `.env` and `.env.local`.

### Post-Processing (`<postProcessing>`)

Noise reduction settings.

| Sub-parameter | Default | Description |
|---------------|---------|-------------|
| `highVolumeThreshold` | `15` | Maximum number of findings in a single file. When a file has more, every finding in it gets a score penalty (risk -25, confidence -30), because such files are usually logs or datasets. Findings are not removed. |

---

## Example `pom.xml`

```xml
<plugin>
    <groupId>dev.nichar</groupId>
    <artifactId>facepalm-maven-plugin</artifactId>
    <version>1.0.2</version>
    <configuration>
        <errorThreshold>85</errorThreshold>
        <failOnWarnings>true</failOnWarnings>
        <!-- Replaces the defaults, so the default directories are repeated. -->
        <skipDirs>.git,.idea,target,node_modules,dist,build,out,bin,logs</skipDirs>
        <evaluators>
            <dummyKeywords>
                <keyword>test_key</keyword>
                <keyword>fake_secret</keyword>
            </dummyKeywords>
        </evaluators>
    </configuration>
</plugin>
```

## CLI Usage

You can override parameters directly from the command line:

```bash
mvn facepalm:scan -Dscoring.strategy=GATEKEEPER -DfailOnWarnings=true -DshowDetails=true
```
