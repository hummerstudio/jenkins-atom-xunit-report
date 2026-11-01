#!/usr/bin/env python3
"""Fail when the atom's numbers and failure list differ from the expected ones.

    python3 tools/assert-report.py expected.json <work-dir> [--unstable=auto|yes|no]
"""
import html
import json
import os
import re
import sys

expected = json.load(open(sys.argv[1], encoding="utf-8"))
work = sys.argv[2]
unstable = "auto"
for arg in sys.argv[3:]:
    if arg.startswith("--unstable="):
        unstable = arg.split("=", 1)[1]

doc = json.load(open(os.path.join(work, "output.json"), encoding="utf-8"))
data = doc.get("data") or {}
problems = []


def value(key):
    field = data.get(key)
    if isinstance(field, dict):
        return field.get("value")
    return None


for key in ("total", "passed", "failed", "skipped", "errors"):
    got = value(key)
    want = expected[key]
    if str(got) != str(want):
        problems.append(f"{key}: atom={got} expected={want}")

formats = [f for f in (value("formats") or "").split(",") if f]
if sorted(formats) != sorted(expected["formats"]):
    problems.append(f"formats: atom={formats} expected={expected['formats']}")

problems_expected = expected["failed"] + expected["errors"] > 0
want_unstable = {"auto": problems_expected, "yes": True, "no": False}[unstable]
got_unstable = value("build.result") == "UNSTABLE"
if got_unstable != want_unstable:
    problems.append(f"build.result: atom={value('build.result')} expected UNSTABLE={want_unstable}")

report = data.get("report") or {}
if not report.get("path"):
    problems.append("no report output")
else:
    page = os.path.join(report["path"], report.get("target") or "index.html")
    if not os.path.exists(page):
        problems.append(f"report page missing: {page}")
    else:
        text = open(page, encoding="utf-8").read()
        listed = [html.unescape(m) for m in re.findall(r"<li>(.*?)</li>", text, re.S)]
        if listed != expected["names"]:
            problems.append(f"failed names: atom={listed} expected={expected['names']}")
        for key in ("total", "passed", "failed", "errors", "skipped"):
            if str(expected[key]) not in text:
                problems.append(f"report page does not show {key}={expected[key]}")

if problems:
    print("report mismatch:\n  " + "\n  ".join(problems), file=sys.stderr)
    raise SystemExit(1)
print("ok: " + ", ".join(f"{k}={expected[k]}" for k in ("total", "passed", "failed", "errors", "skipped"))
      + f" names={len(expected['names'])}")
