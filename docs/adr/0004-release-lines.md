# ADR 0004: Use strict SemVer and forward fixes through release lines

Status: Accepted

## Context

Supported maintenance lines need fixes without receiving new opt-in features or breaking defaults. The same fix
must also reach newer lines without contributors maintaining several copies of a pull request.

## Decision

Use `master` for the next minor/major and `release/X.Y.x` for patch releases. Submit fixes to the oldest affected
supported line. Automation cherry-picks them into the next newer line and eventually `master`; contributors do not
forward-port. New features target `master`. Keep `${revision}` in the POM and derive published versions from tags.

Patch releases contain fixes without contract changes, minor releases add opt-in capabilities, and major releases
permit breaking contract changes. Follow the existing versioning policy for exceptions such as fixes restoring
documented behavior.

## Consequences

The target branch is part of contribution correctness. Standalone closing-keyword lines in PR descriptions survive
forwarding and close the issue when the chain reaches `master`. Conflicts need maintainer review. Release signing,
publishing and forwarding credentials belong to automation, not local contributor builds.

Evidence: [versioning](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/docs/versioning.md),
[contribution rules](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/CONTRIBUTING.md#branches-and-versioning),
[roadmap](../roadmap.md).
