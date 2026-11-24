#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Nikolas Charalambidis
"""Checks the plugin contract for strict SemVer (#338).

Subcommands:
  dump <plugin.xml>
      Prints the contract of the plugin descriptor, one line per plugin, goal and user-settable parameter:
      names, -D properties, types and defaults. Descriptions and read-only parameters are left out.
      The committed dump is src/main/contract/plugin-contract.txt.
  compare <old> <new> [--allowed patch|minor|major]
      Compares two contracts (each a plugin.xml or a dump). An added line is a minor change; a removed or changed
      line is a major one. Writes a Markdown summary (to $GITHUB_STEP_SUMMARY when set, otherwise to stdout) and a
      warning annotation when the level exceeds --allowed.
  japicmp <japicmp.xml> [--allowed patch|minor|major]
      Summarizes the binary-incompatible changes of a japicmp XML report the same way. They are major changes.

Both comparisons only warn: they always exit 0 when the inputs are readable.
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

HEADER = """\
# Plugin contract of facepalm-maven-plugin (#338), generated from META-INF/maven/plugin.xml. Do not edit.
# Regenerate after `mvn package`:
#   python3 .github/scripts/semver-check.py dump target/classes/META-INF/maven/plugin.xml > src/main/contract/plugin-contract.txt
# Under strict SemVer, an added line needs a minor release, and a removed or changed line a major release.
"""

LEVELS = ["none", "patch", "minor", "major"]

PROPERTY = re.compile(r"^\$\{([^}]+)\}$")

# At most this many changed lines or classes are listed in the summary.
MAX_LISTED = 50


def value(text):
    """Formats a value for a key=value pair; quotes it when it contains whitespace or quotes."""
    text = text.strip()
    return '"%s"' % text.replace('"', '\\"') if re.search(r'[\s"]', text) or text == "" else text


def line(kind, name, attributes):
    return " ".join([kind, name] + ["%s=%s" % (k, value(v)) for k, v in attributes if v is not None])


def children(element, tag):
    """Returns the child elements of element's tag child, or none when it is missing."""
    child = element.find(tag)
    return list(child) if child is not None else []


def dump(plugin_xml):
    """Returns the contract lines of a plugin descriptor."""
    root = ET.parse(plugin_xml).getroot()
    lines = [line("plugin", root.findtext("artifactId"), [
        ("goalPrefix", root.findtext("goalPrefix")),
        ("requiredJavaVersion", root.findtext("requiredJavaVersion")),
        ("requiredMavenVersion", root.findtext("requiredMavenVersion")),
    ])]
    for mojo in sorted(root.find("mojos"), key=lambda m: m.findtext("goal")):
        goal = mojo.findtext("goal")
        lines.append(line("goal", goal, [
            ("phase", mojo.findtext("phase")),
            ("requiresProject", mojo.findtext("requiresProject")),
            ("aggregator", mojo.findtext("aggregator")),
            ("threadSafe", mojo.findtext("threadSafe")),
        ]))
        configuration = {c.tag: c for c in children(mojo, "configuration")}
        parameters = [p for p in children(mojo, "parameters") if p.findtext("editable") == "true"]
        for parameter in sorted(parameters, key=lambda p: p.findtext("name")):
            name = parameter.findtext("name")
            config = configuration.get(name)
            text = (config.text or "").strip() if config is not None else ""
            match = PROPERTY.match(text)
            lines.append(line("param", "%s.%s" % (goal, name), [
                ("alias", parameter.findtext("alias")),
                ("type", parameter.findtext("type")),
                ("required", parameter.findtext("required")),
                ("property", match.group(1) if match else None),
                ("expression", text if text and not match else None),
                ("default", config.get("default-value") if config is not None else None),
            ]))
    return lines


def read_contract(path):
    """Reads the contract lines of a plugin.xml or of a dump, without comments and blank lines."""
    if path.endswith(".xml"):
        return dump(path)
    with open(path, encoding="utf-8") as handle:
        return [l.rstrip("\n") for l in handle if l.strip() and not l.startswith("#")]


def write_summary(out):
    summary = "\n".join(out) + "\n"
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(summary)
    else:
        sys.stdout.write(summary)


def verdict(title, level, allowed):
    """Returns the summary line of a check and prints a warning annotation when level exceeds allowed."""
    if LEVELS.index(level) > LEVELS.index(allowed):
        print("::warning title=%s::Needs a %s release, but this branch allows only %s changes" % (
            title, level, allowed))
        return "**Needs a %s release**, but this branch allows only %s changes." % (level, allowed)
    return "Level: %s (allowed: %s)." % (level, allowed)


def listed(lines, prefix):
    out = ["    %s %s" % (prefix, l) for l in lines[:MAX_LISTED]]
    if len(lines) > MAX_LISTED:
        out.append("    ... and %d more" % (len(lines) - MAX_LISTED))
    return out


def compare(args):
    old, new = read_contract(args.old), read_contract(args.new)
    removed = [l for l in old if l not in set(new)]
    added = [l for l in new if l not in set(old)]
    level = "major" if removed else "minor" if added else "none"
    out = ["## Plugin contract", ""]
    out.append("Goals, parameters, `-D` properties, types and defaults, compared with %s." % args.label)
    out.append("")
    out.append(verdict("Plugin contract", level, args.allowed))
    if removed or added:
        out.append("")
        out.append("```diff")
        out.extend(l.strip() for l in listed(removed, "-") + listed(added, "+"))
        out.append("```")
    write_summary(out)
    print("Plugin contract: %s (%d removed or changed, %d added)" % (level, len(removed), len(added)))
    return 0


def japicmp(args):
    root = ET.parse(args.report).getroot()
    incompatible = []
    for cls in root.iter("class"):
        changes = sorted({c.get("type") for c in cls.iter("compatibilityChange")
                          if c.get("binaryCompatible") == "false"})
        if changes:
            incompatible.append("%s: %s" % (cls.get("fullyQualifiedName"), ", ".join(changes)))
    level = "major" if incompatible else "none"
    out = ["## Java API (japicmp)", ""]
    out.append("Binary compatibility of the public and protected classes, compared with %s." % args.label)
    out.append("")
    out.append(verdict("Java API", level, args.allowed))
    if incompatible:
        out.append("")
        out.append("```")
        out.extend(l.strip() for l in listed(incompatible, ""))
        out.append("```")
    write_summary(out)
    print("Java API: %s (%d binary-incompatible classes)" % (level, len(incompatible)))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("dump", help="print the contract of a plugin.xml").add_argument("plugin_xml")
    for name, first in (("compare", "old"), ("japicmp", "report")):
        command = commands.add_parser(name)
        command.add_argument(first)
        if name == "compare":
            command.add_argument("new")
        command.add_argument("--allowed", choices=LEVELS[1:], default="major")
        command.add_argument("--label", default="the last release", help="what is compared with, for the summary")
    args = parser.parse_args()

    if args.command == "dump":
        sys.stdout.write(HEADER + "\n".join(dump(args.plugin_xml)) + "\n")
        return 0
    return compare(args) if args.command == "compare" else japicmp(args)


if __name__ == "__main__":
    sys.exit(main())
