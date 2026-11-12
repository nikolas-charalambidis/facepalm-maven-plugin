<!-- Put each closing keyword on its own line, e.g. "Fixes #42", so it is copied to the forward-port PR. -->
Fixes #

<!-- Why is this change needed? What does it change? -->

**Target branch:** fixes go to the oldest affected `release/X.Y.x` branch (forwarded to `master` automatically); new features go to `master`.

- [ ] `mvn clean verify` passes locally.
- [ ] Formatting applied (`mvn spotless:apply`).
- [ ] Tests added or updated (unit tests, and an integration test fixture for new detection patterns).
- [ ] New or changed parameters documented in `docs/configuration.md`.
- [ ] No real secrets in code, tests or this description.
