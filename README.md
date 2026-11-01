# jenkins-atom-xunit-report

The .NET test reports that are **not** JUnit XML: port of **xunit** (11,755 installs), plus the two shapes the
**nunit** (3,814) and **mstest** (3,707) plugins read. One step, one report page.

## What it reads

- **NUnit 3**: `<test-run testcasecount passed failed skipped inconclusive>` (dotnet test, nunit3-console)
- **NUnit 2**: `<test-results><test-suite><results><test-case>`
- **MSTest / `dotnet test`**: TRX `<TestRun><ResultSummary><Counters>`, both the 2006 and 2010 namespaces
- **xUnit.net**: `<assemblies><assembly total passed failed skipped>`

The shape is detected from the root element, so a workspace that mixes kinds of report (a solution with both NUnit and
MSTest projects, for example) is summed into one page. The `formats` output says which shapes were found.

## Counting rules

Taken from the plugins' own XSLT rather than invented: `tools/oracle/` holds `nunit-to-junit.xsl` and
`mstest-to-junit.xsl` copied verbatim out of the `nunit` and `mstest` hpi, and the smoke test runs them over the same
fixtures to check that this atom agrees.

- **NUnit 3**: `skipped = @skipped + @inconclusive`; whatever `@passed + @failed + @skipped + @inconclusive` does not
  cover (warnings, asserts) is reported as `errors`; `total = @testcasecount`.
- **NUnit 2**: per suite, a failure is a case with a `<failure>` child, a skip is `executed="False"` or
  `result="Inconclusive"`.
- **TRX**: `failures = @failed`, `errors = @error + @timeout`, `skipped = @inconclusive + (@total - @executed)`.
  A data-driven container is not a test (its rows are); a row is named "test row N"; a result whose `StdOut` starts
  with `omit-from-jenkins:true` is left out.
- **xUnit.net**: the assembly attributes.

## Outputs

`total` `passed` `failed` `skipped` `errors` `formats`, plus the embedded `report`. A report the atom cannot read is
skipped and mentioned in the log (a half-written file from an interrupted run must not break the step); if nothing
readable is left, the step fails with `40002`.

The step asks for `UNSTABLE` when anything failed or errored, the way the JUnit publisher does, unless
`skipMarkingUnstable` is set; `failOnFailure` turns the same condition into a failed step.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh              # fixtures checked against the plugins' XSLT, plus the failure paths
python3 tools/mutations.py    # proves the smoke goes red when the atom is wrong
```
