import java.io.File;
import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * Applies one of the plugins' own XSLT files to a report, so the expected JUnit numbers in the smoke test come from
 * Jenkins' rules rather than from a second copy of them. Only used by tools/oracle/check.py.
 */
public final class Xslt {
    private Xslt() {}

    public static void main(String[] args) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer(new StreamSource(new File(args[0])));
        transformer.transform(new StreamSource(new File(args[1])), new StreamResult(new File(args[2])));
    }
}
