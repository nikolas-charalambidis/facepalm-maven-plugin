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

> **Note:** The report goal reads `facepalm-findings.json`, which the scan goal writes to the build directory (`target/` by default). Run `mvn facepalm:scan` (or a full `mvn verify`) before generating the site; otherwise the report says that no scan data is available. Secrets are masked in both the JSON file and the report.
