#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# NUnit 3
dir="$(mktemp -d)"; mkdir -p "$dir/TestResults"
printf '<test-run total="3" passed="2" failed="1" skipped="0"><test-case name="A" result="Passed"/><test-case name="B" result="Failed"/><test-case name="C" result="Passed"/></test-run>' >"$dir/TestResults/nunit.xml"
printf '{"testResults": "**/TestResults/*.xml", "skipMarkingUnstable": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "nunit failed: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "3" ] || fail "nunit total"
[ "$(output "$dir" failed)" = "1" ] || fail "nunit failed"

# MSTest
dir="$(mktemp -d)"
printf '<TestRun><ResultSummary outcome="Failed"><Counters total="4" passed="3" failed="1" notExecuted="0"/></ResultSummary><Results><UnitTestResult testName="M1" outcome="Failed"/></Results></TestRun>' >"$dir/ms.xml"
printf '{"testResults": "ms.xml", "skipMarkingUnstable": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "mstest failed: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "4" ] || fail "mstest total"

# xUnit.net
dir="$(mktemp -d)"
printf '<assemblies><assembly total="2" passed="2" failed="0" skipped="0"/></assemblies>' >"$dir/x.xml"
printf '{"testResults": "x.xml"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "xunit failed"
[ "$(output "$dir" passed)" = "2" ] || fail "xunit passed"

echo "smoke ok: NUnit 3, MSTest, xUnit.net"
