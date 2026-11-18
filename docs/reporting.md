# Reporting

Facepalm provides integrated reporting to help you visualize and track potential secrets in your codebase. Reports are decoupled from the core build-breaking scanner to keep your CI/CD pipelines fast and focused.

## How to Generate Reports

### 1. Add the Scan Plugin to the Build

The report shows the results of the `scan` goal, so the plugin must also be in `<build>` with the `scan` execution, as in the [recommended setup](../README.md#recommended-setup-important). Without it, `mvn verify` never scans and the report always says that no scan data is available. Also, `mvn facepalm:scan` and `mvn facepalm:report` fail with "No plugin found for prefix 'facepalm'".

```xml
<build>
    <plugins>
        <plugin>
            <groupId>dev.nichar</groupId>
            <artifactId>facepalm-maven-plugin</artifactId>
            <version>1.1.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>scan</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 2. Add the Report Plugin

```xml
<reporting>
    <plugins>
        <plugin>
            <groupId>dev.nichar</groupId>
            <artifactId>facepalm-maven-plugin</artifactId>
            <version>1.1.0</version>
        </plugin>
    </plugins>
</reporting>
```

### 3. Use maven-site-plugin 3.7.1 or Later

The report needs `maven-site-plugin` 3.7.1 or later (tested with 3.7.1, 3.9.1, 3.11.0, 3.12.1 and 3.21.0). Maven 3.9 uses 3.12.1 by default, which works. Maven 3.6.3 uses 3.3 by default, which fails with `NoClassDefFoundError: org/apache/maven/doxia/siterenderer/DocumentContent`, so pin a newer version there:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-site-plugin</artifactId>
            <version>3.21.0</version>
        </plugin>
    </plugins>
</build>
```

### 4. Run the Site Goal

To generate the reports, run:

```bash
mvn site
```

The report is written to `target/site/facepalm-report.html` (or the configured `<reporting><outputDirectory>`). To render only this report without the full site, run `mvn facepalm:report`; it writes to `target/reports/facepalm-report.html` (`${project.build.directory}/reports`).

> **Note:** The report goal reads `facepalm-findings.json`, which the scan goal writes to the build directory (`target/` by default). Run `mvn facepalm:scan` (or a full `mvn verify`) before generating the site; otherwise the report says that no scan data is available. The same happens with an empty file, and after a scan that aborted: every scan deletes the results of the previous one before it starts, so the report never shows outdated findings as current. The report lists severities with the names used in `facepalm-findings.json` (`ERROR`, `WARNING`, `INFO`, `SUPPRESSED`) and counts each of them. The Location column shows paths relative to the project directory, so a published site does not reveal the build machine's directories. Fields in the file that the report goal does not know are ignored, so a file written by a newer or older plugin version still renders. Secrets are masked in both the JSON file and the report. The context snippet shows about 60 characters on each side of a finding's value, marked with `...` where the line continues. In it, every value a pattern captured is masked as a whole token, and so is every quoted value, every value after `=` or `:`, and any long token mixing letters and digits, even if no pattern detected it. At most a quarter of a value is shown, up to 4 characters at each end.

## Suppressed Findings

A finding suppressed by an inline `facepalm:ignore` marker or a `.facepalmignore` entry (see the [FAQ](faq.md#how-do-i-handle-false-positives)) stays in the reports, so reviewers can audit it:

- `facepalm-findings.json` lists it with `"finalSeverity": "SUPPRESSED"`; `finalScore`, `riskScore` and `confidenceScore` keep their values. Consumers that expect only `ERROR`, `WARNING` and `INFO` must accept this value.
- The log says how many findings were suppressed, for example `[INFO] 1 findings suppressed by facepalm:ignore markers or .facepalmignore (finalSeverity SUPPRESSED in facepalm-findings.json).`, and `showDetails` adds a `Suppressed:` count.
- The site report counts them under "Suppressed" and lists them with the severity `SUPPRESSED`.
- The score history (`showScoring`) names the source: `Suppressed by facepalm:ignore: <reason>` or `Suppressed by .facepalmignore:<line>: <reason>`.

Suppressed findings never fail the build. A marker on a line without a finding, and a `.facepalmignore` entry that matches no finding, are reported as a `WARNING` with the pattern name `Unused Suppression`. The scan statistics count `.facepalmignore` as a skipped file: it lists suppressions, not content to scan.

## Scan Statistics

Every scan ends with one log line about its size and speed, for example:

```
[INFO] Scanned 1,234 files (56.7 MB) in 3.2 s (17.7 MB/s), 12 skipped
```

The same values are written to `facepalm-statistics.json` next to `facepalm-findings.json`:

| Field | Meaning |
|---|---|
| `filesDiscovered` | Files found below the scan root. |
| `filesScanned` | Files read and analysed. |
| `filesSkipped` | Files excluded by name, size, binary content or an error. |
| `bytesScanned` | Total size of the scanned files in bytes. |
| `durationMillis` | Duration of the scan, from discovery to the last analysed file. |
| `bytesPerSecond` | Scanned bytes per second, or `null` if the scan was too short to measure. |

Sizes in the log use decimal units (1 kB = 1,000 bytes).
