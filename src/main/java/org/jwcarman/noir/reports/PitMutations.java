package org.jwcarman.noir.reports;

import java.nio.file.Path;
import org.w3c.dom.Element;

/** Reads PIT's {@code mutations.xml}. */
public final class PitMutations {
  private PitMutations() {}

  public static PitSummary parse(Path mutationsXml) {
    var mutations = Xml.parse(mutationsXml).getElementsByTagName("mutation");
    int detected = 0;
    for (int i = 0; i < mutations.getLength(); i++) {
      if ("true".equals(((Element) mutations.item(i)).getAttribute("detected"))) {
        detected++;
      }
    }
    return new PitSummary(mutations.getLength(), detected);
  }
}
