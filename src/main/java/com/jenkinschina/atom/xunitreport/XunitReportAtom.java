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
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * xunit as an atom: the .NET test reports that are not JUnit XML (NUnit 3, MSTest, xUnit.net).
 *
 * <p>The JUnit atom reads JUnit; a .NET build writes none of that. Three shapes cover almost every .NET project:
 * {@code test-run} (NUnit 3), {@code TestRun/ResultSummary/Counters} (MSTest/Visual Studio) and
 * {@code assemblies/assembly} (xUnit.net).
 */
public class XunitReportAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("testResults", "**/TestResults/*.xml, **/test-results*.xml, **/nunit*.xml");
        String label = context.getParam("label", ".NET tests");
        String outDir = context.getParam("outputDir", "out/xunit");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);
        boolean skipMarkingUnstable = context.getBooleanParam("skipMarkingUnstable", false);
        boolean allowEmptyResults = context.getBooleanParam("allowEmptyResults", false);

        List<Path> files = match(workspace, pattern);
        if (files.isEmpty()) {
            if (allowEmptyResults) {
                return AtomResult.success().data("total", "0").data("failed", "0");
            }
            return AtomResult.error(ErrorType.USER, 40001, "no .NET test report matched " + pattern);
        }

        Totals totals = new Totals();
        List<String> failed = new ArrayList<>();
        for (Path file : files) {
            read(file, totals, failed);
        }
        if (totals.total == 0) {
            return AtomResult.error(ErrorType.USER, 40002, "no test case found in " + files.size() + " file(s)");
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, pattern, totals, failed), StandardCharsets.UTF_8);
        context.log("[xunit] " + totals.total + " test(s): " + totals.passed + " passed, " + totals.failed
                + " failed, " + totals.skipped + " skipped");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(totals.total))
                .data("passed", String.valueOf(totals.passed))
                .data("failed", String.valueOf(totals.failed))
                .data("skipped", String.valueOf(totals.skipped))
                .report("report", label, dir.toString(), "index.html");
        if (totals.failed > 0 && !skipMarkingUnstable) {
            result.markUnstable();
        }
        if (totals.failed > 0 && failOnFailure) {
            return AtomResult.failure(totals.failed + " test(s) failed")
                    .data("total", String.valueOf(totals.total))
                    .data("failed", String.valueOf(totals.failed))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    private static void read(Path file, Totals totals, List<String> failed) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                return;
            }

            // NUnit 3: <test-run total="12" passed="10" failed="1" skipped="1">
            if ("test-run".equals(root.getTagName())) {
                int passed = number(root.getAttribute("passed"));
                int failedCount = number(root.getAttribute("failed"));
                int skipped = number(root.getAttribute("skipped"));
                if (passed + failedCount + skipped > 0) {
                    // the attributes already summarise the cases; counting both would double the total
                    add(totals, passed, failedCount, skipped);
                    names(root, "test-case", "result", failed);
                } else {
                    collect(root, "test-case", "result", failed, totals);
                }
                return;
            }

            // MSTest / Visual Studio: <TestRun><ResultSummary outcome="Failed"><Counters total="…"/>
            NodeList counters = document.getElementsByTagName("Counters");
            if (counters.getLength() > 0) {
                Element element = (Element) counters.item(0);
                add(totals, number(element.getAttribute("passed")), number(element.getAttribute("failed")),
                        number(element.getAttribute("notExecuted")) + number(element.getAttribute("inconclusive")));
                NodeList results = document.getElementsByTagName("UnitTestResult");
                for (int i = 0; i < results.getLength(); i++) {
                    Element result = (Element) results.item(i);
                    if ("Failed".equalsIgnoreCase(result.getAttribute("outcome")) && failed.size() < 200) {
                        failed.add(result.getAttribute("testName"));
                    }
                }
                return;
            }

            // xUnit.net: <assemblies><assembly total="…" passed="…" failed="…" skipped="…">
            NodeList assemblies = document.getElementsByTagName("assembly");
            if (assemblies.getLength() > 0) {
                for (int i = 0; i < assemblies.getLength(); i++) {
                    Element assembly = (Element) assemblies.item(i);
                    add(totals, number(assembly.getAttribute("passed")), number(assembly.getAttribute("failed")),
                            number(assembly.getAttribute("skipped")));
                }
                return;
            }

            // last resort: count test-case elements by their result attribute
            collect(root, "test-case", "result", failed, totals);
            if (totals.total > 0) {
                return;
            }
            collect(root, "testcase", "result", failed, totals);
        } catch (Exception ignored) {
            // an unreadable report contributes nothing
        }
    }

    /** Only the names, for the report; the counts came from the summary attributes. */
    private static void names(Element root, String tag, String attribute, List<String> failed) {
        NodeList cases = root.getElementsByTagName(tag);
        for (int i = 0; i < cases.getLength() && failed.size() < 200; i++) {
            Element test = (Element) cases.item(i);
            String result = test.getAttribute(attribute);
            if ("Failed".equalsIgnoreCase(result) || "failure".equalsIgnoreCase(result)) {
                failed.add(test.getAttribute("name"));
            }
        }
    }

    private static void collect(Element root, String tag, String attribute, List<String> failed, Totals totals) {
        NodeList cases = root.getElementsByTagName(tag);
        int pass = 0;
        int fail = 0;
        int skip = 0;
        for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            String result = test.getAttribute(attribute);
            if ("Failed".equalsIgnoreCase(result) || "failure".equalsIgnoreCase(result)) {
                fail++;
                if (failed.size() < 200) {
                    failed.add(test.getAttribute("name"));
                }
            } else if ("Skipped".equalsIgnoreCase(result) || "Ignored".equalsIgnoreCase(result)) {
                skip++;
            } else {
                pass++;
            }
        }
        add(totals, pass, fail, skip);
    }

    private static void add(Totals totals, int passed, int failed, int skipped) {
        totals.passed += passed;
        totals.failed += failed;
        totals.skipped += skipped;
        totals.total += passed + failed + skipped;
    }

    private static int number(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
                            || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(200)
                    .forEach(files::add);
        }
        return files;
    }

    private static String render(String label, String pattern, Totals totals, List<String> failed) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(label).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:110px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("ul{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:14px 30px;font-size:13px}")
                .append("</style></head><body>");
        html.append("<h1>").append(label).append("</h1><p class=\"meta\">").append(totals.total)
                .append(" test(s) · <code>").append(pattern).append("</code></p><div class=\"cards\">");
        html.append("<div class=\"card\"><b>").append(totals.passed).append("</b><span>passed</span></div>");
        html.append("<div class=\"card\"><b>").append(totals.failed).append("</b><span>failed</span></div>");
        html.append("<div class=\"card\"><b>").append(totals.skipped).append("</b><span>skipped</span></div></div>");
        if (!failed.isEmpty()) {
            html.append("<ul>");
            for (String name : failed) {
                html.append("<li>").append(name).append("</li>");
            }
            html.append("</ul>");
        }
        return html.append("</body></html>").toString();
    }

    private static final class Totals {
        int total;
        int passed;
        int failed;
        int skipped;

        Totals() {}

        Totals(int passed, int failed, int skipped) {
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
            this.total = passed + failed + skipped;
        }
    }
}
