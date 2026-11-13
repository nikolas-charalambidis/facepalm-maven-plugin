# FAQ

## Why did my build fail?

The **Facepalm Maven Plugin** found a potential secret (API key, password, token) in your project. By default, it fails the build if any finding reaches the `errorThreshold` (default: 80).

**What to do?**
1. Check the console logs. Facepalm will show the file and line number.
2. If it's a real secret: **Rotate it immediately.** Deleting it from the current commit is NOT enough if it was already pushed.
3. If it's a false positive: See below.

---

## How do I handle false positives?

No scanner is perfect. If Facepalm flags something that isn't a secret:

1. **Use placeholders**: Instead of a real key or a realistic-looking fake, use `${API_KEY}` or `PLACEHOLDER`. Facepalm's `PlaceholderSuppressorEvaluator` will automatically ignore these.
2. **Move to test paths**: Files in `test/`, `mock/`, or `spec/` get a significant score reduction.
3. **Add to `.gitignore`**: Facepalm reads your `.gitignore` and reduces the risk score for ignored files.
4. **Adjust Thresholds**: If Facepalm is too sensitive, you can increase the `errorThreshold` in your `pom.xml`.
5. **Add Dummy Keywords**: You can configure `dummyKeywords` in the `<evaluators>` section to tell Facepalm what to ignore.

---

## Performance

### Is it slow?
Facepalm is designed for speed. It uses:
- **Parallel processing**: Scans files using all available CPU cores.
- **Fast filtering**: Quickly skips binary files, large files (>5MB), and excluded directories.
- **Sisu Indexing**: Fast dependency injection and component discovery.

### Memory usage
Facepalm reads files into memory to perform regex matching. To prevent Out-Of-Memory (OOM) errors, it has a default `maxFileSizeBytes` limit (5MB). Large log files or datasets are automatically skipped.

---

## Does it scan my Git history?

No. Facepalm currently only scans the **working directory**. It's designed as a pre-commit shield. If you need to scan your entire Git history for past mistakes, we recommend using tools like **trufflehog** or **gitleaks**.

---

## Why use Facepalm over Gitleaks, and when?

[Gitleaks](https://github.com/gitleaks/gitleaks) is a mature, widely used secret scanner. Facepalm does not replace it. The two tools solve overlapping problems from different angles, so pick based on your setup.

### When Facepalm fits better

- **Maven-native, nothing extra to install.** Facepalm is resolved from Maven Central like any other plugin. If your team already has a JDK and Maven, there is no separate binary to download, pin, or get approved in a locked-down environment.
- **Configuration lives in `pom.xml`.** The plugin version and its settings are versioned with the project and can be inherited from a parent POM, so every module and developer runs the same rules.
- **Enforced by the build itself.** The `scan` goal binds to the `verify` phase, so a plain `mvn verify` in CI enforces it without an extra pipeline step. `failOnError` and `failOnWarnings` give a severity-based hard stop (see [Configuration](configuration.md)).
- **Scoring instead of match/no-match.** Each regex match is scored by evaluators for entropy, location (e.g. test paths), file extension, placeholders, surrounding context, and public exposure (gitignored files are discounted). The combined score maps to `ERROR`, `WARNING`, or `INFO`. This reduces noise without maintaining long allowlists.
- **Maven site integration.** `facepalm:report` adds the findings to `mvn site`, next to reports such as PMD and JaCoCo (see [Reporting](reporting.md)).
- **Scans the whole working tree.** Untracked and unstaged files are checked too, so a secret is caught before it is even staged.

### When Gitleaks fits better

- **Scanning Git history.** Gitleaks can scan past commits; Facepalm scans only the working directory (see [Does it scan my Git history?](#does-it-scan-my-git-history)).
- **Broader rule set.** Gitleaks ships a large, community-maintained set of detection rules.
- **No JVM startup.** Gitleaks is a single Go binary, which keeps pre-commit hooks fast.
- **Non-Maven or polyglot repositories.** Gitleaks works on any repository, regardless of build tool.
- **Ecosystem.** Gitleaks offers a GitHub Action, [pre-commit](https://pre-commit.com/) framework integration, SARIF output, and baselines to ignore findings already known.

### Using both

The tools complement each other. A common setup:

1. **Facepalm** in the Maven build and in the pre-commit hook (see [Git Hooks](git-hooks.md)), as the guardrail every developer runs.
2. **Gitleaks** in CI, scanning the Git history for secrets that slipped in before Facepalm was adopted.

---

## Can I use it outside of Maven?

Use `mvn facepalm:scan`; that is the supported way. For development, the source also contains a command-line entry point (`dev.nichar.facepalm.FacepalmCLI`). It is not published as a runnable JAR, so run it from a checkout with the project's classpath:

```bash
mvn -q package -DskipTests -DskipITs
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/facepalm-maven-plugin-0.0.0-SNAPSHOT.jar:$(cat cp.txt)" dev.nichar.facepalm.FacepalmCLI [--debug] [--output <dir>] [<directory>]
```

- `<directory>`: directory to scan (default: the current one).
- `--output <dir>`: where `facepalm-findings.json` is written (default: `<directory>/target`).
- `--debug`: log at DEBUG level and list processed and skipped files.

It uses the default configuration of the Maven goal; parameters such as `skipDirs` cannot be changed.

---

## Disclaimer

### Is the plugin author responsible for any damage?
No. By using this software, you agree that the author(s) are not responsible for any damage, data loss, or security breaches. This is explicitly stated in the [LICENSE](../LICENSE) and the [README](../README.md). No automated tool is a substitute for proper security practices.
