package org.jwcarman.noir.reports;

import java.io.StringReader;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** Safe XML parsing: no DOCTYPE, no external entities. */
public final class Xml {
  private Xml() {}

  public static Document parse(Path file) {
    try {
      var f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      return f.newDocumentBuilder().parse(file.toFile());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Cannot parse XML report " + file + ": " + e.getMessage(), e);
    }
  }

  /**
   * Parses a JaCoCo report, which declares a DOCTYPE referencing an external DTD. The DTD is never
   * loaded and no external entity is ever resolved.
   */
  public static Document parseJacoco(Path file) {
    try {
      var f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      var builder = f.newDocumentBuilder();
      builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
      return builder.parse(file.toFile());
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Cannot parse XML report " + file + ": " + e.getMessage(), e);
    }
  }
}
