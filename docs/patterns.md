# Detection Patterns

Facepalm includes a pre-defined set of "Gold Standard" patterns for detecting sensitive information. Each finding is processed through a heuristic engine that adjusts risk and confidence levels before determining the final threat level.

## Built-in Detectors

| Category | Detectors |
|----------|-----------|
| **Cloud & Infrastructure** | AWS Access/Secret Keys, Google API Keys, Firebase Keys, Heroku API Keys, Azure OpenAI Keys. |
| **Databases** | MongoDB, PostgreSQL, MySQL, Redis, SQL Server Connection Strings. |
| **AI & LLM Providers** | OpenAI (Project & API Keys), Anthropic, Google Gemini, HuggingFace, Replicate, Cohere, Mistral, Groq. |
| **Payments & Finance** | Stripe Live/Test Secret Keys. |
| **VCS & Collaboration** | GitHub Tokens (Classic & Fine-grained), GitLab, Slack Tokens, Twilio, Mailgun. |
| **Webhooks** | Slack, Discord. |
| **Cryptographic Keys** | Private Key Blocks: PKCS#8 (`PRIVATE KEY`), `ENCRYPTED PRIVATE KEY`, RSA, EC, DSA, OPENSSH, PGP (`PGP PRIVATE KEY BLOCK`). |
| **Generic** | JWT Tokens, Basic Auth Headers, Generic API/Secret/Token/Password assignments. |

## How to Extend

You can add your own custom patterns or override the default ones by configuring the `<patterns>` section in your `pom.xml`.

### Example: Adding a custom pattern

```xml
<configuration>
    <patterns>
        <overrides>
            <pattern>
                <name>My Secret Token</name>
                <pattern>\bmy-token-[0-9a-f]{16}\b</pattern>
                <baseRisk>80</baseRisk>
                <baseConfidence>90</baseConfidence>
                <multiLine>false</multiLine>
            </pattern>
        </overrides>
    </patterns>
</configuration>
```

### Pattern Fields

| Field | Description |
|-------|-------------|
| `name` | Human-readable name for the pattern (used in reports). |
| `pattern` | A Java-compatible Regular Expression. |
| `baseRisk` | Starting risk score (0-100). |
| `baseConfidence` | Starting confidence score (0-100). |
| `multiLine` | If `true`, the pattern matches across multiple lines (e.g., PEM files). |

---

## Heuristic Adjustments

Patterns are only the first step. Every match is analyzed by:
1. **EntropyEvaluator**: Checks the randomness of the string.
2. **LocationEvaluator**: Increases risk for `src/main/` and decreases for `test/`.
3. **PublicExposureEvaluator**: Lowers risk if the file is matched by `.gitignore`.
4. **PlaceholderSuppressorEvaluator**: Prevents false positives from `${VARIABLES}` or dummy data like "replace_me".
5. **CompositeScoringPostProcessor**: Detects if multiple types of secrets appear in the same file.
