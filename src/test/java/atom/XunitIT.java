package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The xunit atom (NUnit 3/2, MSTest TRX, xUnit.net), checked against the plugins' own XSLT rather than against a number
 * copied into the test: {@code tools/oracle/nunit-to-junit.xsl} and {@code mstest-to-junit.xsl} are run over the same
 * fixture and the resulting JUnit XML is summed here. A count the atom gets wrong has to disagree with Jenkins' rule.
 *
 * <p>xUnit.net has no XSLT in the plugin, so its assembly attributes are read directly — the same way the oracle does.
 */
class XunitIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    // ---------------------------------------------------------------- every format, through the default pattern

    @Test
    @DisplayName("nunit3: skipped counts inconclusive, warnings are errors, total is testcasecount")
    void nunit3() throws Exception {
        agreesWithThePlugin("nunit3");
    }

    @Test
    @DisplayName("nunit2: a failure is a <failure> child, a skip is executed=false or Inconclusive")
    void nunit2() throws Exception {
        agreesWithThePlugin("nunit2");
    }

    @Test
    @DisplayName("nunit3 warning: a warning is neither passed nor skipped, so it is reported as an error")
    void nunit3Warning() throws Exception {
        agreesWithThePlugin("nunit3-warning");
    }

    @Test
    @DisplayName("mstest trx: failures, errors = error + timeout, skipped = inconclusive + not executed")
    void mstestTrx() throws Exception {
        agreesWithThePlugin("mstest-trx");
    }

    @Test
    @DisplayName("mstest legacy: the 2006 namespace reads the same counters")
    void mstestLegacy() throws Exception {
        agreesWithThePlugin("mstest-legacy");
    }

    @Test
    @DisplayName("xunit.net: the assembly attributes carry the totals")
    void xunitNet() throws Exception {
        agreesWithThePlugin("xunit-net");
    }

    @Test
    @DisplayName("passing: a green report marks nothing unstable")
    void passing() throws Exception {
        agreesWithThePlugin("passing");
    }

    // ---------------------------------------------------------------- the pattern, and mixing formats

    @Test
    @DisplayName("dotnet test writes TestResults/<guid>/run.trx; the default pattern has to reach it")
    void nestedTrx() throws Exception {
        Map<String, String> files = fixture("mstest-trx");
        String trx = files.remove("TestResults/ci.trx");

        AtomResult run = workspace(files)
                .file("TestResults/8f2a4c1e-dead-beef/run.trx", trx)
                .input("{}")
                .run();

        run.assertOk();
        assertEquals("8", run.field("total"), "nested trx total");
        assertEquals("mstest-trx", run.field("formats"), "nested trx formats");
    }

    @Test
    @DisplayName("an explicit pattern is obeyed: a decoy outside TestResults/ must not be read")
    void explicitPattern() throws Exception {
        Map<String, String> files = fixture("nunit3");
        String suite = files.get("TestResults/nunit3.xml");

        AtomResult run = workspace(files)
                .file("docs/decoy.xml", suite)
                .input("testResults", "TestResults/*.xml")
                .run();

        run.assertOk();
        assertEquals("6", run.field("total"), "the decoy was counted: " + run.document());
    }

    @Test
    @DisplayName("two formats in one workspace add up, and both are named in the output")
    void mixed() throws Exception {
        Map<String, String> files = fixture("nunit3");
        files.putAll(fixture("mstest-trx"));

        AtomResult run = workspace(files).input("{}").run();

        run.assertOk();
        assertAgrees(run, true);
        assertEquals("14", run.field("total"));
        assertTrue(run.field("formats").contains("nunit3") && run.field("formats").contains("mstest-trx"),
                "both formats should be named: " + run.field("formats"));
    }

    // ---------------------------------------------------------------- nothing to read

    @Test
    @DisplayName("an unknown status: an empty workspace is 40001, not a green build with zeros")
    void emptyWorkspace() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertEquals("40001", String.valueOf(run.document().get("errorCode")), String.valueOf(run.document()));
        assertTrue(run.document().containsKey("message") || run.document().containsKey("error"),
                "the failure must say what happened: " + run.document());
        assertTrue(message(run).toLowerCase().contains("no")
                        || String.valueOf(run.document()).contains("40001"),
                "message: " + run.document());
    }

    @Test
    @DisplayName("allowEmptyResults turns that into a green step with zeros")
    void allowEmptyResults() throws Exception {
        AtomResult run = atom.input("allowEmptyResults", "true").run();

        run.assertOk();
        assertEquals("0", run.field("total"));
        assertEquals("0", run.field("failed"));
    }

    @Test
    @DisplayName("a report that is not XML is 40003, and the opt-in still lets it through")
    void garbageXml() throws Exception {
        AtomResult run = atom.file("TestResults/broken.xml", "this is not xml at all")
                .input("testResults", "TestResults/*.xml")
                .run();

        run.assertRefused();
        assertEquals("40003", String.valueOf(run.document().get("errorCode")), String.valueOf(run.document()));

        AtomResult lenient = atom
                .input("testResults", "TestResults/*.xml")
                .input("allowUnreadableReports", "true")
                .input("allowEmptyResults", "true")
                .run();
        lenient.assertOk();
        assertEquals("0", lenient.field("total"), "opt-in total");
    }

    @Test
    @DisplayName("valid XML that is not a report this atom knows is a parse failure, not \"zero tests\"")
    void unrecognisedXml() throws Exception {
        AtomResult run = atom.file("TestResults/empty.xml", "<something><else/></something>")
                .input("testResults", "TestResults/*.xml")
                .run();

        run.assertRefused();
        assertEquals("40003", String.valueOf(run.document().get("errorCode")), String.valueOf(run.document()));
    }

    @Test
    @DisplayName("a report in a known format that simply has no cases is 40002")
    void reportWithoutCases() throws Exception {
        AtomResult run = atom.file("TestResults/empty.xml",
                        "<test-run id=\"1\"><test-suite type=\"Assembly\" name=\"a\"/></test-run>")
                .input("testResults", "TestResults/*.xml")
                .run();

        run.assertRefused();
        assertEquals("40002", String.valueOf(run.document().get("errorCode")), String.valueOf(run.document()));
    }

    @Test
    @DisplayName("a DOCTYPE is refused instead of being expanded (XXE)")
    void doctypeIsRefused() throws Exception {
        AtomResult run = atom.file("TestResults/xxe.xml", "<?xml version=\"1.0\"?>"
                        + "<!DOCTYPE test-run [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<test-run testcasecount=\"1\" passed=\"1\"><test-suite>"
                        + "<test-case name=\"&x;\" result=\"Passed\"/></test-suite></test-run>")
                .input("testResults", "TestResults/*.xml")
                .run();

        run.assertRefused();
        assertFalse(String.valueOf(run.document()).contains("root:"), "the entity was expanded: " + run.document());
        assertFalse(run.console().contains("root:"), "the entity was expanded into the log");
    }

    @Test
    @DisplayName("one broken report does not hide the good ones, and the log says so")
    void partialReports() throws Exception {
        Map<String, String> files = fixture("nunit3");
        files.put("TestResults/half-written.xml", "<broken");

        AtomResult run = workspace(files).input("allowUnreadableReports", "true").run();

        run.assertOk();
        assertEquals("6", run.field("total"), "partial: total");
        assertTrue(run.console().contains("could not be read"), "the log should say a report was skipped:\n"
                + run.console());
    }

    // ---------------------------------------------------------------- the verdict

    @Test
    @DisplayName("failOnFailure does not fail a green report")
    void failOnFailureGreen() throws Exception {
        AtomResult run = workspace(fixture("passing")).input("failOnFailure", "true").run();

        run.assertOk();
        assertEquals("success", run.status());
    }

    @Test
    @DisplayName("failOnFailure turns a red suite into a failed step, keeping the counts")
    void failOnFailureRed() throws Exception {
        AtomResult run = workspace(fixture("mstest-trx"))
                .input("failOnFailure", "true")
                .input("skipMarkingUnstable", "true")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertEquals("1", run.field("failed"), "failOnFailure lost the counts");
    }

    @Test
    @DisplayName("skipMarkingUnstable leaves the build green")
    void skipMarkingUnstable() throws Exception {
        AtomResult run = workspace(fixture("mstest-trx")).input("skipMarkingUnstable", "true").run();

        run.assertOk();
        assertAgrees(run, false);
    }

    @Test
    @DisplayName("without it, a failure marks the build unstable and never fails the step")
    void marksUnstableByDefault() throws Exception {
        AtomResult run = workspace(fixture("mstest-trx")).input("{}").run();

        run.assertOk();
        assertAgrees(run, true);
        assertEquals("UNSTABLE", field(run, "build.result"), "the build should have been marked unstable");
    }

    // ---------------------------------------------------------------- the oracle

    private void agreesWithThePlugin(String name) throws Exception {
        AtomResult run = workspace(fixture(name)).input("{}").run();

        run.assertOk();
        boolean unstable = expected(run.work()).failed + expected(run.work()).errors > 0;
        assertAgrees(run, unstable);
        assertTrue(run.document().containsKey("data"), "no data in the output");
    }

    /** Compare every count, the formats and the failure names with what the plugins' own XSLT says. */
    private void assertAgrees(AtomResult run, boolean expectUnstable) throws Exception {
        Expected want = expected(run.work());
        String detail = run.document().toString();

        assertEquals(String.valueOf(want.total), run.field("total"), "total\n" + detail);
        assertEquals(String.valueOf(want.passed), run.field("passed"), "passed\n" + detail);
        assertEquals(String.valueOf(want.failed), run.field("failed"), "failed\n" + detail);
        assertEquals(String.valueOf(want.skipped), run.field("skipped"), "skipped\n" + detail);
        assertEquals(String.valueOf(want.errors), run.field("errors"), "errors\n" + detail);
        assertEquals(String.join(",", want.formats), run.field("formats"), "formats\n" + detail);

        assertEquals(expectUnstable ? "UNSTABLE" : "", field(run, "build.result"),
                "build.result\n" + detail);

        Map<?, ?> report = data(run).get("report") instanceof Map<?, ?> map ? map : Map.of();
        assertTrue("report".equals(report.get("type")), "no report output: " + report);
        Object target = report.containsKey("target") ? report.get("target") : "index.html";
        Path page = Path.of(String.valueOf(report.get("path"))).resolve(String.valueOf(target));
        assertTrue(Files.isRegularFile(page), "report page missing: " + page);

        String html = Files.readString(page, StandardCharsets.UTF_8);
        assertEquals(want.names, listedNames(html), "the failed names on the page");
        for (Object count : List.of(want.total, want.passed, want.failed, want.errors, want.skipped)) {
            assertTrue(html.contains(String.valueOf(count)),
                    "the report page does not show " + count + ":\n" + html);
        }
    }

    /** What the plugin's XSLT — or, for xUnit.net, its assembly attributes — make of the reports in a workspace. */
    private Expected expected(Path root) throws Exception {
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return (name.endsWith(".xml") || name.endsWith(".trx")) && !name.equals("input.json");
                    })
                    .sorted()
                    .forEach(reports::add);
        }
        if (reports.isEmpty()) {
            throw new IllegalStateException("no report under " + root);
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<String> names = new ArrayList<>();
        Set<String> formats = new LinkedHashSet<>();

        for (Path report : reports) {
            String root_ = localName(parse(report).getDocumentElement());
            Part part;
            switch (root_) {
                case "test-run" -> {
                    formats.add("nunit3");
                    part = sumSuites(transform(report, "nunit-to-junit.xsl"));
                }
                case "test-results" -> {
                    formats.add("nunit2");
                    part = sumSuites(transform(report, "nunit-to-junit.xsl"));
                }
                case "TestRun" -> {
                    formats.add("mstest-trx");
                    part = sumSuites(transform(report, "mstest-to-junit.xsl"));
                }
                case "assemblies" -> {
                    formats.add("xunit-net");
                    part = xunitNative(parse(report));
                }
                default -> throw new IllegalStateException(report + ": no oracle for <" + root_ + ">");
            }
            total += part.tests;
            failures += part.failures;
            errors += part.errors;
            skipped += part.skipped;
            for (String name : part.names) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return new Expected(formats.stream().toList(), total, total - failures - errors - skipped,
                failures, skipped, errors, names);
    }

    private record Part(int tests, int failures, int errors, int skipped, List<String> names) {}

    private record Expected(List<String> formats, int total, int passed, int failed, int skipped, int errors,
                            List<String> names) {}

    /** Sum the JUnit {@code <testsuite>} attributes; the {@code <testsuites>} wrapper only carries partial ones. */
    private Part sumSuites(Document document) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<String> names = new ArrayList<>();
        NodeList suites = document.getElementsByTagName("testsuite");
        for (int index = 0; index < suites.getLength(); index++) {
            Element suite = (Element) suites.item(index);
            tests += integer(suite, "tests");
            failures += integer(suite, "failures");
            errors += integer(suite, "errors");
            skipped += integer(suite, "skipped");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int c = 0; c < cases.getLength(); c++) {
                Element testcase = (Element) cases.item(c);
                NodeList children = testcase.getChildNodes();
                boolean red = false;
                for (int k = 0; k < children.getLength(); k++) {
                    String tag = localName(children.item(k));
                    if ("failure".equals(tag) || "error".equals(tag)) {
                        red = true;
                    }
                }
                if (!red) {
                    continue;
                }
                String name = testcase.getAttribute("name");
                String classname = testcase.getAttribute("classname");
                // the nunit XSL splits a NUnit 2 case name into classname + name; put the fixture back
                if (!name.isEmpty() && !classname.isEmpty() && !name.startsWith(classname + ".")) {
                    name = classname + "." + name;
                }
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return new Part(tests, failures, errors, skipped, names);
    }

    /** xUnit.net: the assembly attributes carry the totals, the {@code <test result="Fail">} elements the names. */
    private Part xunitNative(Document document) {
        int tests = 0;
        int failures = 0;
        int skipped = 0;
        List<String> names = new ArrayList<>();
        NodeList assemblies = document.getElementsByTagName("assembly");
        for (int index = 0; index < assemblies.getLength(); index++) {
            Element assembly = (Element) assemblies.item(index);
            int passed = integer(assembly, "passed");
            int failed = integer(assembly, "failed");
            int skip = integer(assembly, "skipped");
            tests += assembly.hasAttribute("total") ? integer(assembly, "total") : passed + failed + skip;
            failures += failed;
            skipped += skip;
            NodeList cases = assembly.getElementsByTagName("test");
            for (int c = 0; c < cases.getLength(); c++) {
                Element test = (Element) cases.item(c);
                String result = test.getAttribute("result");
                if ("fail".equalsIgnoreCase(result) || "failed".equalsIgnoreCase(result)) {
                    names.add(test.getAttribute("name"));
                }
            }
        }
        return new Part(tests, failures, 0, skipped, names);
    }

    private Document transform(Path report, String xsl) throws Exception {
        Path stylesheet = module().resolve("tools").resolve("oracle").resolve(xsl);
        Templates templates = TransformerFactory.newInstance().newTemplates(new StreamSource(stylesheet.toFile()));
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        DOMResult result = new DOMResult(document);
        templates.newTransformer().transform(new StreamSource(report.toFile()), result);
        return document;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static int integer(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value == null || value.isEmpty() ? 0 : Integer.parseInt(value.trim());
    }

    private static String localName(Node node) {
        if (node == null) {
            return "";
        }
        String name = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static final Pattern LIST_ITEM = Pattern.compile("<li>(.*?)</li>", Pattern.DOTALL);

    /** The failure names the report page lists, in page order. */
    private static List<String> listedNames(String html) {
        List<String> names = new ArrayList<>();
        Matcher matcher = LIST_ITEM.matcher(html);
        while (matcher.find()) {
            names.add(matcher.group(1)
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&amp;", "&"));
        }
        return names;
    }

    // ---------------------------------------------------------------- fixtures

    private Atom workspace(Map<String, String> files) throws Exception {
        Atom instance = Atom.of();
        files.forEach(instance::file);
        return instance;
    }

    /** Every file of {@code tools/fixtures/<name>}, keyed by its path inside the fixture. */
    private Map<String, String> fixture(String name) throws IOException {
        Path root = module().resolve("tools").resolve("fixtures").resolve(name);
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            files.put(root.relativize(path).toString().replace('\\', '/'),
                                    Files.readString(path, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        return files;
    }

    private static Path module() {
        return Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
    }

    private static Map<?, ?> data(AtomResult run) {
        Object data = run.document().get("data");
        return data instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String field(AtomResult run, String key) {
        Object value = data(run).get(key);
        if (value instanceof Map<?, ?> map && map.get("value") != null) {
            return String.valueOf(map.get("value"));
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(AtomResult run) {
        return String.valueOf(run.document().getOrDefault("message", ""));
    }
}
