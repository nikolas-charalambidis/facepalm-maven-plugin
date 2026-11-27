# ADR 0002: Declare Guava explicitly

Status: Accepted

## Context

Maven bundles Guava, and plugin classloading can otherwise expose an incompatible version. The project encountered
a `ClassNotFoundException` for `com.google.common.base.Stopwatch`.

## Decision

Declare Guava explicitly in the POM and exclude Guice's transitive Guava. Preserve the explicit dependency during
dependency upgrades. The CLI jar includes the classes it needs through shading and minimization.

## Consequences

Guava remains a maintained runtime dependency rather than an accidental transitive dependency. Unit tests alone
cannot prove Maven classloader compatibility: run the scan and report integration tests across the supported
Maven matrix. The standalone jar tests also protect against minimization dropping needed classes.

Evidence: [POM](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/pom.xml),
[NFR-01](../requirements.md#nfr-01), [ADR 0001](0001-injection.md).
