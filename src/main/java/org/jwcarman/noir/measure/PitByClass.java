package org.jwcarman.noir.measure;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.noir.reports.PitSummary;
import org.jwcarman.noir.reports.Xml;
import org.w3c.dom.Element;

/** Reads PIT's {@code mutations.xml} per top-level class. */
public final class PitByClass {
  private PitByClass() {}

  public static Map<String, PitSummary> parse(Path mutationsXml) {
    var mutations = Xml.parse(mutationsXml).getElementsByTagName("mutation");
    var total = new LinkedHashMap<String, int[]>();
    for (int i = 0; i < mutations.getLength(); i++) {
      var m = (Element) mutations.item(i);
      var classes = m.getElementsByTagName("mutatedClass");
      if (classes.getLength() == 0) {
        continue;
      }
      var name = classes.item(0).getTextContent().strip();
      int dollar = name.indexOf('$');
      var top = dollar < 0 ? name : name.substring(0, dollar);
      var t = total.computeIfAbsent(top, k -> new int[2]);
      t[0]++;
      if ("true".equals(m.getAttribute("detected"))) {
        t[1]++;
      }
    }
    var result = new LinkedHashMap<String, PitSummary>();
    total.forEach((k, v) -> result.put(k, new PitSummary(v[0], v[1])));
    return result;
  }
}
