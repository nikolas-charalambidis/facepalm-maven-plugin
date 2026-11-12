# Detection Patterns

Facepalm includes a pre-defined set of "Gold Standard" patterns for detecting sensitive information. Each finding is processed through a heuristic engine that adjusts risk and confidence levels before determining the final threat level.

## Built-in Detectors

| Category | Detectors |
|----------|-----------|
| **Cloud & Infrastructure** | AWS Access/Secret Keys, Google API Keys, Firebase Keys, Heroku API Keys, Azure OpenAI Keys. |
| **Databases** | MongoDB, PostgreSQL, MySQL, Redis, SQL Server Connection Strings. |
| **AI & LLM Providers** | OpenAI (Project & API Keys), Anthropic, HuggingFace, Replicate, Cohere, Mistral, Groq. Google Gemini keys use the Google API Key format and are detected by that pattern. |
| **Payments & Finance** | Stripe Live/Test Secret Keys. |
| **VCS & Collaboration** | GitHub Tokens (Classic & Fine-grained), GitLab, Slack Tokens, Twilio, Mailgun. |
| **Webhooks** | Slack, Discord. |
| **Cryptographic Keys** | Private Key Blocks: PKCS#8 (`PRIVATE KEY`), `ENCRYPTED PRIVATE KEY`, RSA, EC, DSA, OPENSSH, PGP (`PGP PRIVATE KEY BLOCK`). |
| **Generic** | JWT Tokens, Basic Auth Headers, Generic API/Secret/Token/Password assignments. |

## Custom Patterns

Custom patterns cannot be configured yet. The `<patterns><overrides>` configuration shown in earlier versions of this page does not work: Maven cannot bind it, and it would replace all built-in patterns instead of adding to them. Support for additional patterns is tracked in [#79](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/79).

To propose a new built-in pattern, see [Adding detection patterns](../CONTRIBUTING.md#adding-detection-patterns).

---

## Heuristic Adjustments

Patterns are only the first step. Every match is analyzed by these evaluators:
1. **EntropyEvaluator**: Raises the score of random-looking values and lowers it for repetitive ones.
2. **LocationEvaluator**: Increases risk for production paths (`src/main/`, `.env`, `config`) and decreases it for test paths (`test`, `mock`, `spec`). See [path markers](configuration.md#heuristics-evaluators).
3. **FileExtensionEvaluator**: Increases risk in configuration files (`.env`, `.properties`, `.yml`, ...) and decreases it in documentation and logs (`.md`, `.txt`, `.log`, ...).
4. **SurroundingContextEvaluator**: Looks at the line before and after the match; words like `example`, `dummy`, `fake`, `mock` lower confidence, `prod` and `live` raise risk.
5. **PublicExposureEvaluator**: Lowers risk if the file is matched by `.gitignore`.
6. **PlaceholderSuppressorEvaluator**: Prevents false positives from `${VARIABLES}` or dummy data like "replace_me".

Then all findings of a file are post-processed:

7. **CompositeScoringPostProcessor**: Raises risk when one file contains several distinct secret types, and lowers the score of every finding in a file with more than `highVolumeThreshold` findings.
