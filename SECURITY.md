# Security Policy

Facepalm is a security tool, so vulnerabilities in the plugin itself are taken seriously.

## Supported versions

| Version | Supported |
|---------|-----------|
| Latest `1.0.x` release | Yes |
| Older releases | No — upgrade to the latest release |

Fixes are released as patch versions of the latest minor line and published to [Maven Central](https://central.sonatype.com/artifact/dev.nichar/facepalm-maven-plugin).

## What counts as a vulnerability

- The plugin **exposes a secret** it found, for example in console output, logs, `facepalm-findings.json` or the site report.
- The plugin can be made to **execute code, read or write files** outside the scanned project, or inject content into reports.
- A problem in the **release pipeline or published artifacts** (signatures, build integrity).

A secret the scanner **does not detect** (false negative) or flags wrongly (false positive) is not a vulnerability: please open a [missed or wrong detection issue](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/new/choose) with a redacted example.

## Reporting a vulnerability

**Do not open a public issue.** Report it privately via [GitHub private vulnerability reporting](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/security/advisories/new).

Please include the plugin version, Maven and JDK versions, the configuration, and steps to reproduce. Never include real secrets — use fake values with the same shape.

You can expect an acknowledgement within 7 days. Once a fix is released, the advisory is published with credit to the reporter, unless you prefer to stay anonymous.
