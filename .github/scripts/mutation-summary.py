#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Nikolas Charalambidis
"""Summarizes a PIT mutations.xml report (#339).

Writes a Markdown summary (to $GITHUB_STEP_SUMMARY when set, otherwise to stdout): the mutation score, the score per
class, and the surviving mutants. Killed, timed-out and memory-error mutants count as detected, as in PIT's own score.
Always exits 0 when the report is readable: the threshold is enforced by PIT (mutationThreshold).

Usage: mutation-summary.py <mutations.xml> [--title TITLE]
"""

import argparse
import collections
import os
import sys
import xml.etree.ElementTree as ET

DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}

# At most this many surviving mutants are listed, so a large run does not flood the summary.
MAX_LISTED = 30

PREFIX = "dev.nichar.facepalm."


def percent(part, total):
    return "n/a" if total == 0 else "%.1f%%" % (100.0 * part / total)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("report")
    parser.add_argument("--title", default="Mutation testing")
    args = parser.parse_args()

    if not os.path.isfile(args.report):
        print("::error::PIT report %s not found" % args.report)
        return 1

    per_class = collections.defaultdict(lambda: [0, 0])
    survivors = []
    for mutation in ET.parse(args.report).getroot().iter("mutation"):
        name = mutation.findtext("mutatedClass").split("$")[0].replace(PREFIX, "")
        status = mutation.get("status")
        per_class[name][0] += 1
        if status in DETECTED:
            per_class[name][1] += 1
        else:
            survivors.append("%s:%s %s (%s)" % (
                name, mutation.findtext("lineNumber"), mutation.findtext("description"), status.lower()))

    total = sum(c[0] for c in per_class.values())
    detected = sum(c[1] for c in per_class.values())
    out = ["## %s" % args.title, ""]
    out.append("Mutation score: **%s** (%d of %d mutants detected)." % (percent(detected, total), detected, total))
    if per_class:
        out.append("")
        out.append("| Class | Detected | Score |")
        out.append("|---|---|---|")
        for name, (count, killed) in sorted(per_class.items(), key=lambda item: item[1][1] / item[1][0]):
            out.append("| `%s` | %d/%d | %s |" % (name, killed, count, percent(killed, count)))
    if survivors:
        out.append("")
        out.append("<details><summary>Surviving mutants (%d)</summary>" % len(survivors))
        out.append("")
        out.append("```")
        out.extend(survivors[:MAX_LISTED])
        if len(survivors) > MAX_LISTED:
            out.append("... and %d more, see the HTML report" % (len(survivors) - MAX_LISTED))
        out.append("```")
        out.append("</details>")

    summary = "\n".join(out) + "\n"
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(summary)
    else:
        sys.stdout.write(summary)
    print("Mutation score: %s (%d/%d)" % (percent(detected, total), detected, total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
