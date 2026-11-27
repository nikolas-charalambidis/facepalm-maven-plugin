# ADR 0003: Treat scoring and severity as public contract

Status: Accepted

## Context

A scanner upgrade can break a previously passing build if thresholds, defaults or detection semantics change.
Users also consume stable pattern names and report fields in suppressions and other tools.

## Decision

Treat documented detection, scoring strategies, thresholds and failure semantics as public contract. Keep separate
risk and confidence scores, clamp adjustments to 0–100, then compute severity through the selected strategy.
Defaults remain `WEIGHTED_QUADRATIC`, ERROR at 80, WARNING at 40, `failOnError=true`, `failOnWarnings=false`.
Changing documented behavior requires the release level specified by the versioning policy.

Risk represents potential impact/exposure; confidence represents how likely a match is a real secret. The default
strategy computes `0.8 * risk * risk / 100 + 0.2 * confidence`. Other strategies combine the same two scores using
different formulas. Keeping the selected strategy and thresholds stable prevents upgrades from silently changing
which findings cross the build-failure boundary.

## Consequences

A new scoring default cannot ship as a quiet patch change. Opt-in additions can precede a new major default.
Boundary/formula tests and default-consistency tests must stay aligned with documentation. Fixes that restore
documented behavior remain fixes even if they cause a previously passing build to fail.

Evidence: [versioning policy](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/versioning.md),
[FR-04](../requirements.md#fr-04), [FR-06](../requirements.md#fr-06), [ADR 0004](0004-release-lines.md).
