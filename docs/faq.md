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

## Can I use it outside of Maven?

Not yet. The source contains a command-line entry point (`dev.nichar.facepalm.FacepalmCLI`) used for development, but it is not published as a runnable JAR and its defaults differ from the Maven goal. Use `mvn facepalm:scan`. A supported CLI is tracked in [#84](https://github.com/nikolas-charalambidis/facepalm-maven-plugin/issues/84).

---

## Disclaimer

### Is the plugin author responsible for any damage?
No. By using this software, you agree that the author(s) are not responsible for any damage, data loss, or security breaches. This is explicitly stated in the [LICENSE](../LICENSE) and the [README](../README.md). No automated tool is a substitute for proper security practices.
