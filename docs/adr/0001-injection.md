# ADR 0001: Use JSR-330 with Sisu and Guice

Status: Accepted

## Context

Maven supplies its own Guice and Sisu container. Guice 7 drops `javax.inject` in favor of `jakarta.inject`.
An annotation migration can compile successfully while components silently disappear from injected lists.

## Decision

Keep component annotations in `javax.inject`, generate the Sisu index during the build, and use Guice 6 for the
standalone CLI and unit tests. Within Maven, the plugin uses Maven's exported Guice. Keep Maven's transitive Guice
off the unit-test classpath so tests exercise the declared version.

## Consequences

New extractors, evaluators and post-processors must use the same annotations and be indexed. A Guice major upgrade
requires checking both execution environments, not just compilation. Architecture and component-wiring tests
protect the choice; Maven integration tests exercise the goals on supported Maven versions.

Evidence: [#167](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/167),
[architecture](../architecture.md#extending-the-scanner), [NFR-05](../requirements.md#nfr-05).
