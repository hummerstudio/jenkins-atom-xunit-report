#!/usr/bin/env python3
"""Expected numbers for a fixture, computed the way Jenkins computes them.

NUnit and MSTest reports are handed to the plugin's own XSLT (tools/oracle/nunit-to-junit.xsl,
mstest-to-junit.xsl, copied verbatim from the nunit 1.x / mstest 1.0.0 hpi) and the resulting JUnit XML is summed here.
xUnit.net has no XSLT in the plugin, so its assemblies are read directly. Nothing in this file knows how the atom is
implemented: it is a second opinion, not a mirror.

    python3 tools/oracle/check.py tools/fixtures/nunit3
    -> {"total": 6, "passed": 3, "failed": 1, "skipped": 1, "errors": 0, "names": ["Sample.CalcTests.Divides"]}
"""
import json
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
NUNIT_XSL = os.path.join(HERE, "nunit-to-junit.xsl")
MSTEST_XSL = os.path.join(HERE, "mstest-to-junit.xsl")


def local(tag):
    return tag.rsplit("}", 1)[-1]


def compile_oracle(workdir):
    classes = os.path.join(workdir, "classes")
    os.makedirs(classes, exist_ok=True)
    subprocess.run(["javac", "-d", classes, os.path.join(HERE, "Xslt.java")], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return classes


def transform(source, xsl, workdir, classes, index):
    out = os.path.join(workdir, f"junit-{index}.xml")
    subprocess.run(["java", "-cp", classes, "Xslt", xsl, source, out], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return ET.parse(out).getroot()


def sum_suites(root):
    """Sum the JUnit <testsuite> attributes; the <testsuites> wrapper only carries partial ones."""
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    names = []
    for suite in root.iter():
        if local(suite.tag) != "testsuite":
            continue
        for key in totals:
            totals[key] += int(suite.get(key, 0) or 0)
        for case in suite.iter():
            if local(case.tag) != "testcase":
                continue
            if any(local(child.tag) in ("failure", "error") for child in case):
                name = case.get("name")
                classname = case.get("classname")
                # the nunit XSL splits a NUnit 2 case name into classname + name; put the fixture back
                if name and classname and not name.startswith(classname + "."):
                    name = f"{classname}.{name}"
                if name and name not in names:
                    names.append(name)
    return totals, names


def xunit_native(path):
    """xUnit.net: the assembly elements carry the totals, the <test result="Fail"> elements the names."""
    root = ET.parse(path).getroot()
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    names = []
    for assembly in root.iter():
        if local(assembly.tag) != "assembly":
            continue
        passed = int(assembly.get("passed", 0) or 0)
        failed = int(assembly.get("failed", 0) or 0)
        skipped = int(assembly.get("skipped", 0) or 0)
        totals["tests"] += int(assembly.get("total", passed + failed + skipped) or 0)
        totals["failures"] += failed
        totals["skipped"] += skipped
        for test in assembly.iter():
            if local(test.tag) == "test" and (test.get("result") or "").lower() in ("fail", "failed"):
                names.append(test.get("name"))
    return totals, names


def main():
    directory = sys.argv[1]
    reports = []
    for root, _, files in os.walk(directory):
        for name in sorted(files):
            if name.endswith((".xml", ".trx")) and name != "input.json":
                reports.append(os.path.join(root, name))
    if not reports:
        raise SystemExit(f"no report under {directory}")

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    names = []
    formats = []
    workdir = tempfile.mkdtemp(prefix="oracle-")
    classes = compile_oracle(workdir)
    for index, report in enumerate(sorted(reports)):
        tag = local(ET.parse(report).getroot().tag)
        if tag in ("test-run", "test-results"):
            format_name = "nunit3" if tag == "test-run" else "nunit2"
            part, part_names = sum_suites(transform(report, NUNIT_XSL, workdir, classes, index))
        elif tag == "TestRun":
            format_name = "mstest-trx"
            part, part_names = sum_suites(transform(report, MSTEST_XSL, workdir, classes, index))
        elif tag == "assemblies":
            format_name = "xunit-net"
            part, part_names = xunit_native(report)
        else:
            raise SystemExit(f"{report}: no oracle for <{tag}>")
        if format_name not in formats:
            formats.append(format_name)
        for key in totals:
            totals[key] += part[key]
        names.extend(n for n in part_names if n not in names)

    result = {
        "formats": formats,
        "total": totals["tests"],
        "failed": totals["failures"],
        "errors": totals["errors"],
        "skipped": totals["skipped"],
        "passed": totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"],
        "names": names,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
