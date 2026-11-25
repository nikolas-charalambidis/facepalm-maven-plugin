# Git Hooks

For Facepalm to be effective, it should catch secrets before they are
committed.\
Once a secret enters Git history, removing it is difficult and often
incomplete.

------------------------------------------------------------------------

## Pre-commit Hook (recommended)

Runs on every `git commit`. If Facepalm detects a secret, the commit is
blocked.

Create (or edit):

```sh
.git/hooks/pre-commit
```
```sh
#!/bin/sh

mvn -B -ntp facepalm:scan
RESULT=$?

if [ $RESULT -ne 0 ]; then
    echo "Facepalm: potential secret detected. Commit blocked."
    echo "Check the Maven output above for details."
    exit 1
fi

exit 0
```

### Make it executable

```bash
chmod +x .git/hooks/pre-commit
```

------------------------------------------------------------------------

## Pre-push Hook (optional)

Runs on `git push`. Useful as a second line of defense.

Create (or edit):

```sh
.git/hooks/pre-push
```
```sh
#!/bin/sh

mvn -B -ntp facepalm:scan
RESULT=$?

if [ $RESULT -ne 0 ]; then
    echo "Facepalm: push blocked due to potential secrets."
    exit 1
fi

exit 0
```

------------------------------------------------------------------------

## Without Maven

Projects that do not build with Maven, or machines without Maven, can run the self-contained jar instead (see
[Command Line](cli.md)). It needs only a Java 17 runtime. Keep the jar outside the scanned directory, or in a
directory that is skipped, such as `.git`:

```sh
#!/bin/sh

java -jar .git/hooks/facepalm-maven-plugin-cli.jar --output "$(mktemp -d)" .
RESULT=$?

if [ $RESULT -ne 0 ]; then
    echo "Facepalm: potential secret detected. Commit blocked."
    exit 1
fi

exit 0
```

Download the jar once, for example to `.git/hooks/facepalm-maven-plugin-cli.jar`. `--output` keeps the reports out of
the working tree. The CLI takes the goal's parameters as options, such as `--fail-on-warnings`; exit code `1` means
findings fail the scan, `2` that it could not run (see [Command Line](cli.md#exit-codes)).

------------------------------------------------------------------------

## Important notes

-   The hooks use `mvn -B -ntp` (batch mode, no download progress) rather than
    `mvn -q`: quiet mode hides warnings, so with `failOnWarnings=true` a
    blocked commit would show no file or line.

-   The scan covers the **working tree**, not only the staged changes:
    unstaged edits and untracked files are scanned too, and a secret in an
    unrelated file blocks the commit.

-   Git hooks are **not shared by default**. Each developer must install
    them locally.

-   For teams, consider committing hooks via a tool (e.g. custom setup
    script, or frameworks like Husky).

-   Hooks can be bypassed with:

    ```bash
    git commit --no-verify
    ```

------------------------------------------------------------------------

## Why use hooks?

-   **Local and immediate** --- secrets never leave your machine\
-   **Prevents history poisoning** --- cleanup requires rewriting Git
    history\
-   **Acts as a safety net** --- catches mistakes early
