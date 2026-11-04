package org.jwcarman.noir.reports;

import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** Safe XML parsing: no DOCTYPE, no external entities. */
final class Xml {
  private Xml() {}

  static Document parse(Path file) {
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
}
