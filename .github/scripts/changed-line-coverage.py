#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Nikolas Charalambidis
"""Reports the unit-test coverage of the Java lines a pull request adds or changes (#335).

Reads the JaCoCo XML report and the diff between two commits, then writes a Markdown summary
(to $GITHUB_STEP_SUMMARY when set, otherwise to stdout) with the overall and the changed-line coverage.
Uncovered changed lines get a GitHub warning annotation; changed-line coverage below the minimum gets
one more. It only warns and always exits 0 when the inputs are readable: the build is gated by
jacoco:check on the whole bundle.

Usage: changed-line-coverage.py <jacoco.xml> <base-commit> [--head HEAD] [--min 80] [--source-root src/main/java]
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# At most this many per-line annotations, so a large uncovered change does not flood the PR.
MAX_LINE_ANNOTATIONS = 20

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def changed_lines(base, head, source_root):
    """Returns {path: set of added or changed line numbers in head} for the Java files under source_root."""
    diff = subprocess.run(
        ["git", "diff", "--no-color", "--no-ext-diff", "-U0", base, head, "--", source_root],
        check=True, capture_output=True, text=True).stdout
    result = {}
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            target = line[4:]
            path = target[2:] if target.startswith("b/") and target.endswith(".java") else None
        elif path and line.startswith("@@"):
            match = HUNK.match(line)
            if match:
                start = int(match.group(1))
                count = int(match.group(2)) if match.group(2) is not None else 1
                result.setdefault(path, set()).update(range(start, start + count))
    return {p: lines for p, lines in result.items() if lines}


def read_jacoco(report, source_root):
    """Returns (overall {counter: (covered, total)}, {path: {line: covered}}) for executable lines."""
    root = ET.parse(report).getroot()
    overall = {}
    for counter in root.findall("counter"):
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        overall[counter.get("type")] = (covered, covered + missed)
    lines = {}
    for package in root.iter("package"):
        for source in package.findall("sourcefile"):
            path = "/".join([source_root, package.get("name"), source.get("name")]).replace("//", "/")
            per_line = {}
            for line in source.findall("line"):
                if int(line.get("mi")) + int(line.get("ci")) > 0:
                    per_line[int(line.get("nr"))] = int(line.get("ci")) > 0
            lines[path] = per_line
    return overall, lines


def ranges(numbers):
    """Formats sorted line numbers as compact ranges, e.g. 3-5, 9."""
    out = []
    for n in sorted(numbers):
        if out and n == out[-1][1] + 1:
            out[-1][1] = n
        else:
            out.append([n, n])
    return ", ".join(str(a) if a == b else "%d-%d" % (a, b) for a, b in out)


def percent(covered, total):
    return "n/a" if total == 0 else "%.1f%%" % (100.0 * covered / total)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("report", help="JaCoCo XML report, e.g. target/site/jacoco/jacoco.xml")
    parser.add_argument("base", help="commit to diff against, e.g. HEAD^1 on a pull request merge commit")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--min", type=float, default=80.0, help="changed-line coverage minimum in percent")
    parser.add_argument("--source-root", default="src/main/java")
    args = parser.parse_args()

    if not os.path.isfile(args.report):
        print("::error::JaCoCo report %s not found" % args.report)
        return 1

    overall, coverage = read_jacoco(args.report, args.source_root)
    changes = changed_lines(args.base, args.head, args.source_root)

    rows = []
    uncovered_all = []
    total_covered = total_lines = 0
    for path in sorted(changes):
        file_coverage = coverage.get(path, {})
        executable = [n for n in changes[path] if n in file_coverage]
        if not executable:
            continue
        covered = [n for n in executable if file_coverage[n]]
        uncovered = [n for n in executable if not file_coverage[n]]
        total_covered += len(covered)
        total_lines += len(executable)
        uncovered_all.extend((path, n) for n in sorted(uncovered))
        rows.append("| `%s` | %d/%d | %s | %s |" % (
            path, len(covered), len(executable), percent(len(covered), len(executable)),
            ranges(uncovered) or "-"))

    out = ["## Coverage", ""]
    out.append("| Scope | Lines | Branches |")
    out.append("|---|---|---|")
    line_c, line_t = overall.get("LINE", (0, 0))
    branch_c, branch_t = overall.get("BRANCH", (0, 0))
    out.append("| Overall | %s (%d/%d) | %s (%d/%d) |" % (
        percent(line_c, line_t), line_c, line_t, percent(branch_c, branch_t), branch_c, branch_t))
    out.append("| Changed lines | %s (%d/%d) | - |" % (
        percent(total_covered, total_lines), total_covered, total_lines))
    out.append("")
    if rows:
        out.append("| File | Covered | % | Uncovered changed lines |")
        out.append("|---|---|---|---|")
        out.extend(rows)
    else:
        out.append("No executable lines changed under `%s`." % args.source_root)
    out.append("")
    out.append("Changed-line minimum: %.0f%% (warning only). Unit tests count; integration tests do not." % args.min)

    summary = "\n".join(out) + "\n"
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(summary)
    else:
        sys.stdout.write(summary)

    for path, n in uncovered_all[:MAX_LINE_ANNOTATIONS]:
        print("::warning file=%s,line=%d,title=Uncovered changed line::Not covered by unit tests" % (path, n))
    if total_lines and 100.0 * total_covered / total_lines < args.min:
        print("::warning title=Changed-line coverage::%s of the changed lines are covered (%d/%d), below %.0f%%" % (
            percent(total_covered, total_lines), total_covered, total_lines, args.min))
    print("Changed-line coverage: %s (%d/%d)" % (percent(total_covered, total_lines), total_covered, total_lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
