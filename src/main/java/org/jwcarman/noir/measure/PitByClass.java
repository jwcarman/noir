package org.jwcarman.noir.measure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
      var top = topLevel(m);
      if (top == null) {
        continue;
      }
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

  /** One mutant: its source line (0 when PIT gave none) and whether a test detected it. */
  public record Mutant(int line, boolean detected) {}

  /**
   * Mutants per top-level class, in report order. {@code detected} follows the same rule as {@link
   * #parse}: PIT's {@code detected="true"}.
   */
  public static Map<String, List<Mutant>> parseLines(Path mutationsXml) {
    var mutations = Xml.parse(mutationsXml).getElementsByTagName("mutation");
    var result = new LinkedHashMap<String, List<Mutant>>();
    for (int i = 0; i < mutations.getLength(); i++) {
      var m = (Element) mutations.item(i);
      var top = topLevel(m);
      if (top == null) {
        continue;
      }
      int line = 0;
      var lines = m.getElementsByTagName("lineNumber");
      if (lines.getLength() > 0) {
        try {
          line = Integer.parseInt(lines.item(0).getTextContent().strip());
        } catch (NumberFormatException e) {
          line = 0;
        }
      }
      result
          .computeIfAbsent(top, k -> new ArrayList<>())
          .add(new Mutant(line, "true".equals(m.getAttribute("detected"))));
    }
    return result;
  }

  private static String topLevel(Element mutation) {
    var classes = mutation.getElementsByTagName("mutatedClass");
    if (classes.getLength() == 0) {
      return null;
    }
    var name = classes.item(0).getTextContent().strip();
    int dollar = name.indexOf('$');
    return dollar < 0 ? name : name.substring(0, dollar);
  }
}
