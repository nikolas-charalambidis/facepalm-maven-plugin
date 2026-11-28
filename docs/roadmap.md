# Roadmap

Snapshot: 2026-11-27. GitHub milestones are the source of truth; issue membership and status can change. Themes
below describe plans, not delivery dates or features already available in a release.

| Milestone | Theme |
|---|---|
| [1.0.6](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/milestone/12) | Maintenance/security work on `release/1.0.x`; no open issues in this snapshot |
| [1.1.1](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/milestone/10) | Maintenance and release integrity: signed tags, PR title conventions and curated release notes |
| [1.2.0](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/milestone/11) | Opt-in capabilities and contributor tooling: standalone CLI distribution, custom patterns, configuration files, reporting, performance work and architecture documentation |
| [2.0.0](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/milestone/8) | Compatibility-sensitive defaults and scoring/detection improvements, after 1.2.0 |
| [Backlog](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/milestone/7) | Accepted work without a scheduled version |

## Current development and remaining work

The development branch already contains a runnable CLI, staged scanning, scan-error policy, architecture tests,
benchmarks and mutation testing. Development-branch availability does not imply publication in the latest release,
and a broad milestone issue can remain open after some of its implementation has landed.

Examples of remaining 1.2.0 work include [additive custom patterns (#79)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/79),
[configuration files (#240)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/240),
[HTML/SARIF output wiring (#80)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/80) and
[scan memory exhaustion (#17)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/17).
Benchmark history and regression reporting are [planned separately (#247)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/247).

For 2.0.0, [additive skip directories by default (#259)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/259)
and [order-independent score clamping (#81)](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/81)
illustrate changes that need compatibility review. They are not current guarantees.

See [architecture](architecture.md), [requirements](requirements.md) and the [release-line decision](adr/0004-release-lines.md).
