#!/usr/bin/env python3
"""Prove the smoke test can go red: plant one bug at a time in the atom and require it to be caught.

Each entry is a literal edit in XunitReportAtom.java. The smoke is run with the edit in place and must fail; the source
is restored afterwards, even when a mutation slips through (reported as a failure, not an exception).

    python3 tools/mutations.py
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "src/main/java/com/jenkinschina/atom/xunitreport/XunitReportAtom.java")

MUTATIONS = [
    {
        "name": "nunit3-inconclusive",
        "why": "count only @skipped and drop @inconclusive, which nunit-to-junit.xsl adds to skipped",
        "old": "int skippedTotal = skipped + inconclusive;",
        "new": "int skippedTotal = skipped;",
    },
    {
        "name": "trx-ignore-error",
        "why": "forget that TRX separates error and timeout from failed",
        "old": 'int errors = number(counters.getAttribute("error")) + number(counters.getAttribute("timeout"));',
        "new": "int errors = 0;",
    },
    {
        "name": "trx-notexecuted",
        "why": "trust @notExecuted instead of @inconclusive + @total - @executed",
        "old": 'int skipped = Math.max(0, number(counters.getAttribute("inconclusive")) + total - executed);',
        "new": 'int skipped = number(counters.getAttribute("notExecuted"));',
    },
    {
        "name": "drop-trx-glob",
        "why": "stop matching .trx, so dotnet test's own extension is invisible",
        "old": '"**/TestResults/*.trx, **/*.trx, **/TestResults/*.xml, ',
        "new": '"**/TestResults/*.xml, ',
    },
    {
        "name": "count-dd-container",
        "why": "name every UnitTestResult, including the data-driven container the XSL skips",
        "old": 'if (!type.isEmpty() && !"DataDrivenDataRow".equals(type)',
        "new": 'if (false && !type.isEmpty() && !"DataDrivenDataRow".equals(type)',
    },
    {
        "name": "drop-failure-child",
        "why": "count NUnit 2 failures from @result instead of the <failure> child the XSL counts",
        "old": 'if (!directChildren(testCase, "failure").isEmpty()) {\n                    fail++;',
        "new": 'if (!directChildren(testCase, "failure").isEmpty()) {\n                    fail += 0;',
    },
]


def main():
    original = open(SOURCE, encoding="utf-8").read()
    failures = []
    try:
        for mutation in MUTATIONS:
            if mutation["old"] not in original:
                print(f"FAIL {mutation['name']}: the code it edits is gone")
                failures.append(mutation["name"])
                continue
            open(SOURCE, "w", encoding="utf-8").write(original.replace(mutation["old"], mutation["new"], 1))
            result = subprocess.run(["bash", "tools/smoke.sh"], cwd=ROOT, capture_output=True, text=True)
            open(SOURCE, "w", encoding="utf-8").write(original)
            if result.returncode == 0:
                print(f"FAIL {mutation['name']}: the smoke stayed green ({mutation['why']})")
                failures.append(mutation["name"])
            else:
                lines = [line.strip() for line in (result.stderr + result.stdout).splitlines() if line.strip()]
                reason = ""
                for index, line in enumerate(lines):
                    if "report mismatch" in line and index + 1 < len(lines):
                        reason = lines[index + 1]
                        break
                    if "smoke failed" in line:
                        reason = line
                        break
                reason = reason or (lines[-1] if lines else "")
                print(f"ok   {mutation['name']}: red as expected — {reason[:110]}")
    finally:
        open(SOURCE, "w", encoding="utf-8").write(original)
    if failures:
        print("mutations not caught: " + ", ".join(failures))
        raise SystemExit(1)
    print(f"mutations ok: {len(MUTATIONS)} bugs, all caught")


if __name__ == "__main__":
    main()
