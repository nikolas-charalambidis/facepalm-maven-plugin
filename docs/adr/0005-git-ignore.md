# ADR 0005: Let Git classify ignored and tracked files

Status: Accepted

## Context

Git ignore semantics include negation, escaping, depth precedence and rules above the scan root. A simple glob
parser cannot faithfully implement all of them. Machine-specific global exclusions would also make scores vary.

## Decision

In a repository, ask Git which paths are tracked and which untracked paths are ignored, using NUL-separated
`ls-files` output and only per-directory `.gitignore` rules. Exclude `.git/info/exclude` and global ignore settings
from the exposure decision. Disable `core.fsmonitor` so reading Git state does not invoke a configured monitor.

Use the result to discount risk for ignored, untracked files; do not remove these files from scanning. Tracked
files retain public exposure. Without Git, outside a repository or after a Git failure, use the parsed local-glob
fallback, which does not implement negation. Git subprocesses have a timeout.

## Consequences

Normal scans remain usable without Git, with reduced classification accuracy. Ignore rules do not constitute
suppression or exclusion controls. CLI staged scanning separately requires Git and fails clearly without it.

Evidence: [#9](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/9),
[Git ignore tests](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/blob/master/src/test/java/dev/nichar/facepalm/engine/GitIgnoreServiceTest.java),
[FR-04](../requirements.md#fr-04), [architecture](../architecture.md#scan-data-flow).
