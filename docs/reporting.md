# Reporting

Facepalm provides integrated reporting to help you visualize and track potential secrets in your codebase. Reports are decoupled from the core build-breaking scanner to keep your CI/CD pipelines fast and focused.

## How to Generate Reports

### 1. Add the Report Plugin

```xml
<reporting>
    <plugins>
        <plugin>
            <groupId>dev.nichar</groupId>
            <artifactId>facepalm-maven-plugin</artifactId>
            <version>1.0.2</version>
        </plugin>
    </plugins>
</reporting>
```

### 2. Run the Site Goal

To generate the reports, run:

```bash
mvn site
```

The report is written to `target/site/facepalm-report.html` (or the configured `<reporting><outputDirectory>`). To render only this report without the full site, run `mvn facepalm:report`.

> **Note:** The report goal reads `facepalm-findings.json`, which the scan goal writes to the build directory (`target/` by default). Run `mvn facepalm:scan` (or a full `mvn verify`) before generating the site; otherwise the report says that no scan data is available. Secrets are masked in both the JSON file and the report. In the context snippet (the source line of a finding), every value a pattern captured on that line is masked as a whole token, and so is any long token mixing letters and digits, even if no pattern detected it. At most a quarter of a value is shown, up to 4 characters at each end.

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
