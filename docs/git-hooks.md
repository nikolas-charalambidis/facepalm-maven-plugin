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
[Command Line](cli.md)). It needs only a Java 17 runtime and `git`. `--staged` scans only the files staged for the
commit, with their staged content, so the hook is fast and checks exactly what is committed:

```sh
#!/bin/sh

java -jar .git/hooks/facepalm-maven-plugin-cli.jar --staged --output "$(mktemp -d)" .
RESULT=$?

if [ $RESULT -eq 1 ]; then
    echo "Facepalm: potential secret detected. Commit blocked."
    exit 1
elif [ $RESULT -ne 0 ]; then
    echo "Facepalm: the scan could not run (exit code $RESULT). Commit blocked."
    exit 1
fi

exit 0
```

Download the jar once, for example to `.git/hooks/facepalm-maven-plugin-cli.jar`, where it is neither committed nor
scanned. `--output` keeps the reports out of the working tree.

- **What `--staged` scans:** added, modified, copied and renamed files below the scanned directory. A secret that was
  staged and then removed from the working copy still blocks the commit; a secret only in an unstaged change or an
  untracked file does not. Deleted files and submodules are not scanned, and `skipDirs`, the binary filter, the size
  limit and suppressions apply as in a full scan.
- **Exit codes:** `1` means findings fail the scan, `2` that it could not run, for example outside a Git repository
  (see [Command Line](cli.md#exit-codes)). The CLI takes the goal's parameters as options, such as `--fail-on-warnings`.

------------------------------------------------------------------------

## Important notes

-   The hooks use `mvn -B -ntp` (batch mode, no download progress) rather than
    `mvn -q`: quiet mode hides warnings, so with `failOnWarnings=true` a
    blocked commit would show no file or line.

-   The Maven hooks scan the **working tree**, not only the staged changes
    (the CLI's `--staged` scans only those, see above):
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
