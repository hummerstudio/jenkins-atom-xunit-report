#!/usr/bin/env bash
# Every scenario is checked twice: the atom reads the report, and tools/oracle/check.py derives the expected numbers
# from the plugin's own XSLT (nunit-to-junit.xsl, mstest-to-junit.xsl) run over the same file. A count the atom gets
# wrong has to disagree with Jenkins' rule, not with a number copied into this script.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

contains() { grep -q -- "$2" "$1" || fail "$(basename "$1"): missing '$2'"; }
absent() { grep -q -- "$2" "$1" && fail "$(basename "$1"): unexpected '$2'"; }

# one fixture, the atom on the left and the plugin's XSLT on the right
agree() {  # agree <fixture-dir> <work-dir> [--unstable=...]
  python3 tools/oracle/check.py "$1" >"$2/expected.json" || fail "oracle failed on $1"
  python3 tools/assert-report.py "$2/expected.json" "$2" "${@:3}" || fail "$(basename "$1"): atom disagrees with the plugin XSLT"
}

## 1. every format, through the default pattern
for fixture in nunit3 nunit2 nunit3-warning mstest-trx mstest-legacy xunit-net passing; do
  dir="$(mktemp -d)"
  cp -R "tools/fixtures/$fixture/." "$dir/"
  printf '{}' >"$dir/input.json"
  run "$dir" >"$dir/log.txt" 2>&1 || fail "$fixture: step failed: $(cat "$dir/output.json")"
  agree "tools/fixtures/$fixture" "$dir"
  contains "$dir/output.json" '"report"'
done

## 2. dotnet test writes TestResults/<guid>/run.trx; the default pattern has to reach it
dir="$(mktemp -d)"; mkdir -p "$dir/TestResults/8f2a4c1e-dead-beef"
cp tools/fixtures/mstest-trx/TestResults/ci.trx "$dir/TestResults/8f2a4c1e-dead-beef/run.trx"
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "nested trx: step failed: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "8" ] || fail "nested trx: total=$(output "$dir" total)"
[ "$(output "$dir" formats)" = "mstest-trx" ] || fail "nested trx: formats=$(output "$dir" formats)"

## 3. an explicit pattern is obeyed: the decoy outside TestResults/ must not be read
dir="$(mktemp -d)"; mkdir -p "$dir/docs"
cp -R tools/fixtures/nunit3/. "$dir/"
cp tools/fixtures/nunit3/TestResults/nunit3.xml "$dir/docs/decoy.xml"
printf '{"testResults": "TestResults/*.xml"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "pattern: step failed: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "6" ] || fail "pattern: decoy was counted (total=$(output "$dir" total))"

## 4. two formats in one workspace add up, and both are named in the output
dir="$(mktemp -d)"
cp -R tools/fixtures/nunit3/. "$dir/"
cp tools/fixtures/mstest-trx/TestResults/ci.trx "$dir/TestResults/ci.trx"
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "mixed: step failed: $(cat "$dir/output.json")"
python3 tools/oracle/check.py "$dir" >"$dir/expected.json" || fail "mixed: oracle failed"
python3 tools/assert-report.py "$dir/expected.json" "$dir" || fail "mixed: atom disagrees"
[ "$(output "$dir" total)" = "14" ] || fail "mixed: total=$(output "$dir" total)"

## 5. unknown status handling: nothing matched
dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "empty workspace did not fail"; fi
contains "$dir/output.json" '"errorCode":40001'
contains "$dir/output.json" '"error"'

## 6. allowEmptyResults turns that into a green step with zeros
printf '{"allowEmptyResults": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "allowEmptyResults: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "0" ] || fail "allowEmptyResults: total=$(output "$dir" total)"

## 7. a report that is not XML, and a report with no cases, are both 40002
dir="$(mktemp -d)"; mkdir -p "$dir/TestResults"
printf 'this is not xml at all' >"$dir/TestResults/broken.xml"
printf '{"testResults": "TestResults/*.xml"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "garbage xml did not fail"; fi
contains "$dir/output.json" '"errorCode":40002'

dir="$(mktemp -d)"; mkdir -p "$dir/TestResults"
printf '<something><else/></something>' >"$dir/TestResults/empty.xml"
printf '{"testResults": "TestResults/*.xml"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "a report without cases did not fail"; fi
contains "$dir/output.json" '"errorCode":40002'

## 8. a DOCTYPE is refused instead of being expanded (XXE)
dir="$(mktemp -d)"; mkdir -p "$dir/TestResults"
printf '<?xml version="1.0"?><!DOCTYPE test-run [<!ENTITY x SYSTEM "file:///etc/passwd">]><test-run testcasecount="1" passed="1"><test-suite><test-case name="&x;" result="Passed"/></test-suite></test-run>' >"$dir/TestResults/xxe.xml"
printf '{"testResults": "TestResults/*.xml"}' >"$dir/input.json"
if run "$dir" >"$dir/log.txt" 2>&1; then fail "a DOCTYPE document was accepted"; fi
absent "$dir/output.json" 'root:'

## 9. one broken report does not hide the good ones, and the log says so
dir="$(mktemp -d)"
cp -R tools/fixtures/nunit3/. "$dir/"
printf '<broken' >"$dir/TestResults/half-written.xml"
printf '{}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "partial: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "6" ] || fail "partial: total=$(output "$dir" total)"
contains "$dir/log.txt" 'could not be read'

## 10. failOnFailure: a red suite fails the step, a green one does not
dir="$(mktemp -d)"
cp -R tools/fixtures/passing/. "$dir/"
printf '{"failOnFailure": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "failOnFailure on a green report: $(cat "$dir/output.json")"
[ "$(status "$dir")" = "success" ] || fail "green build not success but $(status "$dir")"

dir="$(mktemp -d)"
cp -R tools/fixtures/mstest-trx/. "$dir/"
printf '{"failOnFailure": "true", "skipMarkingUnstable": "true"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "failOnFailure ignored"; fi
[ "$(status "$dir")" = "failure" ] || fail "failOnFailure status=$(status "$dir")"
[ "$(output "$dir" failed)" = "1" ] || fail "failOnFailure lost the counts"

## 11. skipMarkingUnstable leaves the build green
dir="$(mktemp -d)"
cp -R tools/fixtures/mstest-trx/. "$dir/"
printf '{"skipMarkingUnstable": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "skipMarkingUnstable: $(cat "$dir/output.json")"
agree "tools/fixtures/mstest-trx" "$dir" --unstable=no

## 12. without it, failures mark the build unstable (never failing the step)
dir="$(mktemp -d)"
cp -R tools/fixtures/mstest-trx/. "$dir/"
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "unstable: $(cat "$dir/output.json")"
agree "tools/fixtures/mstest-trx" "$dir" --unstable=yes

echo "smoke ok: nunit3 (inconclusive + warning), nunit2, trx (2010 + 2006 namespaces, data-driven, omit-from-jenkins), xunit.net, mixed, default pattern, explicit pattern, XXE, partial reports, failOnFailure, unstable"
