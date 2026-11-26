# Detection Patterns

Facepalm includes a pre-defined set of "Gold Standard" patterns for detecting sensitive information. Each finding is processed through a heuristic engine that adjusts risk and confidence levels before determining the final threat level.

## Built-in Detectors

| Category | Detectors |
|----------|-----------|
| **Cloud & Infrastructure** | AWS Access Keys (long-term `AKIA` and temporary `ASIA`) and Secret Keys, Google API Keys, Firebase Keys, Heroku API Keys, Azure OpenAI Keys, Azure Storage Account Keys (connection strings and storage key variables). |
| **Databases** | MongoDB, PostgreSQL, MySQL, Redis, SQL Server Connection Strings. |
| **AI & LLM Providers** | OpenAI (project, service account and admin keys, legacy API keys), Anthropic, HuggingFace, Replicate, Cohere, Mistral, Groq. Google Gemini keys use the Google API Key format and are detected by that pattern. |
| **Payments & Finance** | Stripe Live/Test Secret Keys. |
| **VCS & Collaboration** | GitHub Tokens (personal `ghp_`, OAuth `gho_`, user-to-server `ghu_`, server-to-server `ghs_`, refresh `ghr_`, and fine-grained), GitLab, Slack Tokens, Twilio, Mailgun. |
| **Webhooks** | Slack, Discord. |
| **Cryptographic Keys** | Private Key Blocks: PKCS#8 (`PRIVATE KEY`), `ENCRYPTED PRIVATE KEY`, RSA, EC, DSA, OPENSSH, PGP (`PGP PRIVATE KEY BLOCK`). |
| **Generic** | JWT Tokens, Basic Auth Headers, Generic API/Secret/Token/Password assignments (`key=value`, `key: value`, XML elements such as `<password>…</password>` in Maven `settings.xml`, and `name="…" value="…"` attribute pairs). |

Patterns match the line as written, quotes included. A key and its value may be quoted (`"password": "…"`, `'password': '…'`), and a quoted value may be a text block (`"""…"""`, `'''…'''`) or have a string prefix (Python `r"…"`, `f'…'`, `b"…"`, C# `@"…"`, `$"…"`, Rust `r#"…"#`). The reported value is the text between the quotes. An escaped (`\"`) or doubled (`''`) quote inside it belongs to the value. A call or placeholder that only names a secret, such as `getenv("GITHUB_TOKEN")` or `"${VAULT_TOKEN}"`, is not reported.

## Custom Patterns

Custom detectors extend the built-in registry. Configure them with mutable definitions under `<patterns><overrides>`:

```xml
<patterns>
    <overrides>
        <pattern>
            <name>Organization Credential</name>
            <regex>ORG_[A-Za-z0-9]{24}</regex>
            <risk>90</risk>
            <confidence>95</confidence>
            <multiLine>false</multiLine>
        </pattern>
    </overrides>
    <disabledPatterns>
        <name>Basic Auth Header</name>
    </disabledPatterns>
</patterns>
```

`name`, `regex`, `risk` and `confidence` are required. Scores must be between 0 and 100; `multiLine` defaults to `false`. Maven expressions are supported. Names are case-sensitive, with surrounding whitespace ignored. Duplicate names (including built-in names), unknown disabled names, invalid definitions and disabling every detector fail configuration. Custom patterns cannot replace a built-in with the same name.

Absent or empty overrides keep the built-ins. Disabled names may identify built-in or custom detectors; other patterns can still detect the same value. `<disabledPatterns>` also accepts comma-separated names.

Patterns compile once when Maven binds the configuration. Single-line patterns use the first participating capture group, or the whole match when no group participates. `multiLine=true` scans the whole file and reports the full match; use Java inline flags such as `(?s)` for dot-all matching and `(?m)` for line anchors. Matches retain the existing scoring, suppression and masking behavior. For overlapping matches, higher base confidence wins; on equal confidence built-ins precede custom detectors.

Only use regexes from trusted configuration and follow the bounded matching guidance below: Java regexes can backtrack excessively, and compilation does not prove linear-time behavior. No automatic timeout is applied to custom regexes.

To propose a new built-in pattern, see [Adding detection patterns](../CONTRIBUTING.md#adding-detection-patterns).

### Linear-time matching

Every pattern must match in linear time, so one long line (minified JavaScript, a base64 blob) or a crafted file cannot hang a build or a pre-commit hook:

- **Bound every gap and every open-ended run that can fail late.** Use `.{0,100}?` instead of `.*`, and give a body that must be closed by a delimiter a maximum length.
- **Bound repeated groups**, for example `(?:_[A-Z0-9]+){1,16}` instead of `(?:_[A-Z0-9]+)+`. Java's regex engine recurses once per repetition of a group, so an unbounded group overflows the stack on a long line, and the file is dropped from the scan. Repeating a single character or character class (`[A-Za-z0-9]{10,}`) is not recursive and needs no bound. A group that repeats once per run instead of once per character, such as the escape-aware value of the generic patterns (runs of plain characters, then an escape such as `\"`), is unrolled and possessive instead.
- **Anchor where a token starts** when its prefix can repeat inside a long run. For example, JWTs use `(?<![A-Za-z0-9_\-]{6})eyJ`: a token may start at most 5 characters into a base64url run. This keeps tokens after `%3D` or `=`, and each run has only a few possible starts.

`SecretPatternBacktrackingTest` enforces this for every default pattern in every build:

- **Measure:** it counts the characters the regex engine reads for adversarial inputs of three lengths (about 1,000, 2,000 and 4,000 characters). The test fails when the reads per added character grow from the shorter step to the longer one by more than 10 %. Fixed costs cancel out, so even a small quadratic term behind a large linear cost is caught.
- **Runaway matching:** more than 5,000 reads per input character stops the match and fails the test, so exponential backtracking cannot hang the build.
- **Inputs:** generic separators and keywords, plus inputs derived from the pattern's samples in `SecretPatternSamplesTest`: prefixes, prefixes followed by a run of the next character, and every run of one to three tokens repeated, alone and after the part of the sample before it.
- **New patterns:** a new pattern is covered once its samples exist. A default pattern without samples fails the build.

The test does not measure absolute speed: a linear pattern with a high constant cost passes.

---

## Heuristic Adjustments

Each secret value is reported once per file. When several patterns match the same value, or overlapping parts of one line, only the most specific pattern reports it: the one with the highest base confidence. For example, `aws_secret_access_key=...` is reported as `AWS Secret Key`, not also as `Generic API Key Assignment`.

Patterns are only the first step. Every match is analyzed by these evaluators:
1. **EntropyEvaluator**: Raises the score of random-looking values and lowers it for repetitive ones. Predictable values such as `A1b2C3d4` or `0123456789abcdef` get no bonus, although they use many distinct characters.
2. **LocationEvaluator**: Increases risk for production paths (`src/main/`, `.env`, `config`) and decreases it for test paths (`test`, `mock`, `spec`). See [path markers](configuration.md#heuristics-evaluators).
3. **FileExtensionEvaluator**: Increases risk in configuration files (`.env`, `.properties`, `.yml`, ...) and decreases it in documentation and logs (`.md`, `.txt`, `.log`, ...).
4. **SurroundingContextEvaluator**: Looks at the line before and after the match; words like `example`, `dummy`, `fake`, `mock` lower confidence, `prod` and `live` raise risk.
5. **PublicExposureEvaluator**: Lowers risk if `.gitignore` ignores the file and Git does not track it. Inside a repository, Git decides both (`git ls-files`), with the full `.gitignore` rules; see the [FAQ](faq.md#how-do-i-handle-false-positives). A tracked file is public even when ignored, so it keeps its risk.
6. **PlaceholderSuppressorEvaluator**: Prevents false positives from `${VARIABLES}` and other templating syntaxes (`$(VAR)`, `#{...}`, `~{...}~`), predictable dummies (`AAAA`, `qwerty`), code or format descriptions matched as a value, and dummy data like "replace_me".

Then all findings of a file are post-processed:

7. **CompositeScoringPostProcessor**: Raises risk when one file contains several distinct secret types, and lowers the score of generic findings in a file with more than `highVolumeThreshold` reported findings. Format-specific findings (AWS keys, GitHub tokens, private keys, ...) are never lowered for volume. Last, a Maven-encrypted value (`{...}`, `{[name=master,...]...}`) is capped just below `errorThreshold`, so it is reported at most as a WARNING.
