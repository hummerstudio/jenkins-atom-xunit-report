package com.jenkinschina.atom.xunitreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The .NET test reports that are not JUnit XML, as one step.
 *
 * <p>The three report shapes a .NET build writes, and the plugin that reads each one in Jenkins:
 * <ul>
 *   <li>NUnit 3 {@code <test-run testcasecount passed failed skipped inconclusive>} (the nunit plugin)</li>
 *   <li>NUnit 2 {@code <test-results><test-suite><results><test-case>} (the same plugin, older format)</li>
 *   <li>MSTest / dotnet-test TRX {@code <TestRun><ResultSummary><Counters>} (the mstest plugin)</li>
 *   <li>xUnit.net {@code <assemblies><assembly>} (the xunit plugin)</li>
 * </ul>
 *
 * <p>The counting rules are copied from the plugins' own XSLT, not invented:
 * {@code nunit-to-junit.xsl} turns a test-run into JUnit with {@code skipped = @skipped + @inconclusive} and
 * {@code errors = cases - passed - skipped - failed - inconclusive}; {@code mstest-to-junit.xsl} turns a TRX into
 * JUnit with {@code failures = @failed}, {@code errors = @error + @timeout} and
 * {@code skipped = @inconclusive + @total - @executed}. Both are in {@code tools/oracle/} and the smoke test runs them
 * over the same fixtures to check this class agrees.
 */
public class XunitReportAtom implements AtomTask {

    /** .trx is dotnet test's own extension; the other patterns are what the plugins document. */
    private static final String DEFAULT_PATTERN =
            "**/TestResults/*.trx, **/*.trx, **/TestResults/*.xml, **/test-results*.xml, **/nunit*.xml, **/xunit*.xml";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("testResults", DEFAULT_PATTERN);
        String label = context.getParam("label", ".NET tests");
        String outDir = context.getParam("outputDir", "out/xunit");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);
        boolean skipMarkingUnstable = context.getBooleanParam("skipMarkingUnstable", false);
        boolean allowEmptyResults = context.getBooleanParam("allowEmptyResults", false);

        List<Path> files = match(workspace, pattern);
        if (files.isEmpty()) {
            if (allowEmptyResults) {
                return AtomResult.success().data("total", "0").data("passed", "0").data("failed", "0")
                        .data("skipped", "0").data("errors", "0").data("formats", "");
            }
            return AtomResult.error(ErrorType.USER, 40001, "no .NET test report matched " + pattern);
        }

        Totals totals = new Totals();
        List<String> failedNames = new ArrayList<>();
        Set<String> formats = new LinkedHashSet<>();
        int unreadable = 0;
        for (Path file : files) {
            if (!read(file, totals, failedNames, formats)) {
                unreadable++;
            }
        }
        // a file that matched but could not be parsed is not the same as "no tests": say so in the log
        if (unreadable > 0) {
            context.log("[xunit] " + unreadable + " of " + files.size() + " report(s) could not be read");
        }
        if (totals.total == 0) {
            if (allowEmptyResults) {
                return AtomResult.success().data("total", "0").data("passed", "0").data("failed", "0")
                        .data("skipped", "0").data("errors", "0").data("formats", String.join(",", formats));
            }
            return AtomResult.error(ErrorType.USER, 40002,
                    "no test case found in " + files.size() + " report(s): " + pattern);
        }

        String formatList = String.join(",", formats);
        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, pattern, formatList, totals, failedNames),
                StandardCharsets.UTF_8);
        context.log("[xunit] " + totals.total + " test(s): " + totals.passed + " passed, " + totals.failed
                + " failed, " + totals.errors + " error(s), " + totals.skipped + " skipped [" + formatList + "]");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(totals.total))
                .data("passed", String.valueOf(totals.passed))
                .data("failed", String.valueOf(totals.failed))
                .data("skipped", String.valueOf(totals.skipped))
                .data("errors", String.valueOf(totals.errors))
                .data("formats", formatList)
                .report("report", label, dir.toString(), "index.html");
        // the JUnit publisher marks a build UNSTABLE when anything did not pass, errors included
        if (totals.failed + totals.errors > 0 && !skipMarkingUnstable) {
            result.markUnstable();
        }
        if (totals.failed + totals.errors > 0 && failOnFailure) {
            return AtomResult.failure(totals.failed + " failed and " + totals.errors + " errored test(s), of "
                    + totals.total)
                    .data("total", String.valueOf(totals.total))
                    .data("passed", String.valueOf(totals.passed))
                    .data("failed", String.valueOf(totals.failed))
                    .data("skipped", String.valueOf(totals.skipped))
                    .data("errors", String.valueOf(totals.errors))
                    .data("formats", formatList)
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /** @return false when the file is not a report this atom can read. */
    private static boolean read(Path file, Totals totals, List<String> failed, Set<String> formats) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            return false;  // a half-written report from an interrupted test run must not break the step
        }
        Element root = document.getDocumentElement();
        if (root == null) {
            return false;
        }

        String name = localName(root);
        int before = totals.total;
        int beforeFailed = totals.failed;
        int beforeErrors = totals.errors;
        switch (name) {
            case "test-run" -> {
                nunit3(root, totals, failed);
                formats.add("nunit3");
            }
            case "test-results" -> {
                nunit2(root, totals, failed);
                formats.add("nunit2");
            }
            case "TestRun" -> {
                mstest(root, totals, failed);
                formats.add("mstest-trx");
            }
            case "assemblies" -> {
                fxunit(root, totals, failed);
                formats.add("xunit-net");
            }
            default -> {
                if (!generic(root, totals, failed)) {
                    return false;
                }
                formats.add("generic");
            }
        }
        return totals.total > before || totals.failed > beforeFailed || totals.errors > beforeErrors
                || "test-run".equals(name) || "test-results".equals(name) || "TestRun".equals(name);
    }

    /**
     * NUnit 3. The summary attributes are the counts; the cases are only walked for the failure names.
     * skipped counts inconclusive too, and what is left over (warnings, asserts) is an error, exactly as
     * nunit-to-junit.xsl does it.
     */
    private static void nunit3(Element root, Totals totals, List<String> failed) {
        List<Element> cases = descendants(root, "test-case");
        int passed = number(root.getAttribute("passed"));
        int failedCount = number(root.getAttribute("failed"));
        int skipped = number(root.getAttribute("skipped"));
        int inconclusive = number(root.getAttribute("inconclusive"));
        int total = number(root.getAttribute("testcasecount"));
        if (total == 0) {
            total = cases.size();
        }
        int errors = Math.max(0, cases.size() - passed - skipped - failedCount - inconclusive);
        int skippedTotal = skipped + inconclusive;
        int passedTotal = Math.max(0, total - failedCount - skippedTotal - errors);
        totals.add(passedTotal, failedCount, skippedTotal, errors, total);
        for (Element testCase : cases) {
            if ("Failed".equalsIgnoreCase(testCase.getAttribute("result"))) {
                addName(failed, testCase.getAttribute("name"));
            }
        }
    }

    /**
     * NUnit 2. No usable summary at the top, so each suite's cases are counted: failures are the cases with a
     * {@code <failure>} child, skipped are {@code executed="False"} or {@code result="Inconclusive"}.
     */
    private static void nunit2(Element root, Totals totals, List<String> failed) {
        for (Element suite : descendants(root, "test-suite")) {
            List<Element> cases = new ArrayList<>();
            for (Element results : directChildren(suite, "results")) {
                cases.addAll(directChildren(results, "test-case"));
            }
            if (cases.isEmpty()) {
                continue;  // a suite that only groups other suites
            }
            int fail = 0;
            int skip = 0;
            for (Element testCase : cases) {
                boolean skipped = "False".equalsIgnoreCase(testCase.getAttribute("executed"))
                        || "Inconclusive".equalsIgnoreCase(testCase.getAttribute("result"));
                if (!directChildren(testCase, "failure").isEmpty()) {
                    fail++;
                    addName(failed, testCase.getAttribute("name"));
                }
                if (skipped) {
                    skip++;
                }
            }
            int pass = Math.max(0, cases.size() - fail - skip);
            totals.add(pass, fail, Math.min(skip, cases.size() - fail), 0, pass + fail + Math.min(skip, cases.size() - fail));
        }
    }

    /**
     * MSTest / dotnet test TRX. The counters are the summary; skipped is inconclusive plus the tests that never ran
     * (total - executed), and error + timeout are errors, which is what mstest-to-junit.xsl puts in the JUnit
     * attributes. Data-driven containers are not tests themselves and are skipped when naming the failures.
     */
    private static void mstest(Element root, Totals totals, List<String> failed) {
        Element counters = first(descendants(root, "Counters"));
        List<Element> results = countedResults(root);
        if (counters != null) {
            int total = number(counters.getAttribute("total"));
            int fail = number(counters.getAttribute("failed"));
            int errors = number(counters.getAttribute("error")) + number(counters.getAttribute("timeout"));
            int executed = counters.hasAttribute("executed") ? number(counters.getAttribute("executed")) : total;
            int skipped = Math.max(0, number(counters.getAttribute("inconclusive")) + total - executed);
            int passed = Math.max(0, total - fail - errors - skipped);
            totals.add(passed, fail, skipped, errors, total);
        } else {
            int pass = 0;
            int fail = 0;
            int skip = 0;
            int errors = 0;
            for (Element result : results) {
                String outcome = result.getAttribute("outcome");
                if ("Passed".equalsIgnoreCase(outcome)) {
                    pass++;
                } else if ("Failed".equalsIgnoreCase(outcome)) {
                    fail++;
                } else if ("NotExecuted".equalsIgnoreCase(outcome) || "Inconclusive".equalsIgnoreCase(outcome)) {
                    skip++;
                } else {
                    errors++;
                }
            }
            totals.add(pass, fail, skip, errors, pass + fail + skip + errors);
        }
        for (Element result : results) {
            String outcome = result.getAttribute("outcome");
            if (!outcome.isEmpty() && !"Passed".equalsIgnoreCase(outcome)
                    && !"NotExecuted".equalsIgnoreCase(outcome) && !"Inconclusive".equalsIgnoreCase(outcome)) {
                // a data-driven row is "test row N" in the plugin's JUnit, not just "test"
                String name = result.getAttribute("testName");
                String row = result.getAttribute("dataRowInfo");
                addName(failed, row.isEmpty() ? name : name + " row " + row);
            }
        }
    }

    /**
     * The results mstest-to-junit.xsl treats as tests: rows of a data-driven test and plain results, not the
     * container of a data-driven test, and nothing that opted out with {@code omit-from-jenkins:true}.
     */
    private static List<Element> countedResults(Element root) {
        List<Element> counted = new ArrayList<>();
        for (Element result : descendants(root, "UnitTestResult")) {
            String type = result.getAttribute("resultType");
            if (!type.isEmpty() && !"DataDrivenDataRow".equals(type)
                    && !("DataDrivenTest".equals(type) && directChildren(result, "InnerResults").isEmpty())) {
                continue;
            }
            String stdout = textOf(firstChild(result, "Output"), "StdOut");
            if (stdout != null && stdout.stripLeading().startsWith("omit-from-jenkins:true")) {
                continue;
            }
            counted.add(result);
        }
        return counted;
    }

    /** xUnit.net: every assembly carries its own totals. */
    private static void fxunit(Element root, Totals totals, List<String> failed) {
        for (Element assembly : descendants(root, "assembly")) {
            int pass = number(assembly.getAttribute("passed"));
            int fail = number(assembly.getAttribute("failed"));
            int skip = number(assembly.getAttribute("skipped"));
            int total = number(assembly.getAttribute("total"));
            if (total == 0) {
                total = pass + fail + skip;
            }
            totals.add(pass, fail, skip, 0, total);
            for (Element test : descendants(assembly, "test")) {
                String result = test.getAttribute("result");
                if ("Fail".equalsIgnoreCase(result) || "Failed".equalsIgnoreCase(result)) {
                    addName(failed, test.getAttribute("name"));
                }
            }
        }
    }

    /** Anything else that still looks like a case list: count by result attribute, no summary to trust. */
    private static boolean generic(Element root, Totals totals, List<String> failed) {
        List<Element> cases = descendants(root, "test-case");
        if (cases.isEmpty()) {
            cases = descendants(root, "testcase");
        }
        if (cases.isEmpty()) {
            return false;
        }
        int pass = 0;
        int fail = 0;
        int skip = 0;
        for (Element testCase : cases) {
            String result = testCase.getAttribute("result");
            if ("Failed".equalsIgnoreCase(result) || "failure".equalsIgnoreCase(result)
                    || "Error".equalsIgnoreCase(result)) {
                fail++;
                addName(failed, testCase.getAttribute("name"));
            } else if ("Skipped".equalsIgnoreCase(result) || "Ignored".equalsIgnoreCase(result)
                    || "Inconclusive".equalsIgnoreCase(result) || "NotExecuted".equalsIgnoreCase(result)) {
                skip++;
            } else {
                pass++;
            }
        }
        totals.add(pass, fail, skip, 0, pass + fail + skip);
        return true;
    }

    private static void addName(List<String> failed, String name) {
        if (failed.size() < 200 && name != null && !name.isBlank()) {
            failed.add(name);
        }
    }

    private static int number(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        if (local == null) {
            local = element.getTagName();
            int colon = local.indexOf(':');
            if (colon >= 0) {
                local = local.substring(colon + 1);
            }
        }
        return local;
    }

    private static List<Element> descendants(Element root, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", name);
        for (int i = 0; i < nodes.getLength(); i++) {
            found.add((Element) nodes.item(i));
        }
        if (found.isEmpty() && root.getElementsByTagName(name).getLength() > 0) {
            nodes = root.getElementsByTagName(name);
            for (int i = 0; i < nodes.getLength(); i++) {
                found.add((Element) nodes.item(i));
            }
        }
        return found;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName((Element) node))) {
                found.add((Element) node);
            }
        }
        return found;
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    /** Text of {@code parent/grandchild}, across the TRX namespaces (2006 and 2010 both occur). */
    private static String textOf(Element parent, String grandchild) {
        if (parent == null) {
            return null;
        }
        Element child = firstChild(parent, grandchild);
        return child == null ? null : child.getTextContent();
    }

    private static Element first(List<Element> elements) {
        return elements.isEmpty() ? null : elements.get(0);
    }

    private static List<Path> match(Path workspace, String pattern) throws Exception {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path))
                            || m.matches(Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(200)
                    .forEach(files::add);
        }
        return files;
    }

    private static String render(String label, String pattern, String formats, Totals totals, List<String> failed) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:110px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("ul{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:14px 30px;font-size:13px}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(totals.total)
                .append(" test(s) · <code>").append(escape(pattern)).append("</code> · ").append(escape(formats))
                .append("</p><div class=\"cards\">");
        html.append("<div class=\"card\"><b>").append(totals.passed).append("</b><span>passed</span></div>");
        html.append("<div class=\"card\"><b>").append(totals.failed).append("</b><span>failed</span></div>");
        html.append("<div class=\"card\"><b>").append(totals.errors).append("</b><span>errors</span></div>");
        html.append("<div class=\"card\"><b>").append(totals.skipped).append("</b><span>skipped</span></div></div>");
        if (!failed.isEmpty()) {
            html.append("<ul>");
            for (String name : failed) {
                html.append("<li>").append(escape(name)).append("</li>");
            }
            html.append("</ul>");
        }
        return html.append("</body></html>").toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class Totals {
        int total;
        int passed;
        int failed;
        int skipped;
        int errors;

        Totals() {}

        void add(int passed, int failed, int skipped, int errors, int total) {
            this.passed += passed;
            this.failed += failed;
            this.skipped += skipped;
            this.errors += errors;
            this.total += total;
        }
    }
}
