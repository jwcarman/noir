package org.jwcarman.noir.measure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jwcarman.noir.reports.Xml;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads JaCoCo's {@code report.xml}. */
public final class JacocoReport {
  private JacocoReport() {}

  /** Coverage per top-level class, keyed by dotted FQN; nested classes are merged in. */
  public static Map<String, ClassCoverage> parse(Path jacocoXml) {
    var doc = Xml.parseJacoco(jacocoXml);
    var covered = new LinkedHashMap<String, int[]>();
    var methods = new LinkedHashMap<String, List<MethodCrap>>();
    var sources = new LinkedHashMap<String, String>();
    var noDebug = new java.util.HashSet<String>();
    var classes = doc.getElementsByTagName("class");
    for (int i = 0; i < classes.getLength(); i++) {
      var cls = (Element) classes.item(i);
      var top = topLevel(cls.getAttribute("name"));
      var lines = covered.computeIfAbsent(top, k -> new int[2]);
      var list = methods.computeIfAbsent(top, k -> new ArrayList<>());
      sources.putIfAbsent(top, cls.getAttribute("sourcefilename"));
      if (cls.getAttribute("sourcefilename").isEmpty() || instructionsWithoutLines(cls)) {
        noDebug.add(top);
      }
      for (Node n = cls.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (!(n instanceof Element e)) {
          continue;
        }
        if (e.getTagName().equals("counter") && e.getAttribute("type").equals("LINE")) {
          lines[0] += count(e, "covered");
          lines[1] += count(e, "missed");
        } else if (e.getTagName().equals("method")) {
          list.add(method(top, e));
        }
      }
    }
    var sourceLines = sourceLines(doc);
    var result = new LinkedHashMap<String, ClassCoverage>();
    covered.forEach((name, c) -> {
      int dot = name.lastIndexOf('.');
      var key = (dot < 0 ? "" : name.substring(0, dot)) + "/" + sources.get(name);
      result.put(
          name,
          new ClassCoverage(
              name,
              c[0],
              c[1],
              methods.get(name),
              sources.get(name),
              noDebug.contains(name),
              sourceLines.getOrDefault(key, Map.of())));
    });
    return result;
  }

  /**
   * Line to covered ({@code ci > 0}) for each {@code <sourcefile>}, keyed by dotted package, "/",
   * and source file name.
   */
  private static Map<String, Map<Integer, Boolean>> sourceLines(org.w3c.dom.Document doc) {
    var result = new LinkedHashMap<String, Map<Integer, Boolean>>();
    var files = doc.getElementsByTagName("sourcefile");
    for (int i = 0; i < files.getLength(); i++) {
      var file = (Element) files.item(i);
      var pkg =
          file.getParentNode() instanceof Element p && p.getTagName().equals("package")
              ? p.getAttribute("name").replace('/', '.')
              : "";
      var lines =
          result.computeIfAbsent(pkg + "/" + file.getAttribute("name"), k -> new TreeMap<>());
      for (Node n = file.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e && e.getTagName().equals("line")) {
          int nr = count(e, "nr");
          if (nr > 0) {
            lines.merge(nr, count(e, "ci") > 0, Boolean::logicalOr);
          }
        }
      }
    }
    return result;
  }

  private static boolean instructionsWithoutLines(Element cls) {
    int instructions = 0;
    int lines = 0;
    for (Node n = cls.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && e.getTagName().equals("counter")) {
        int total = count(e, "covered") + count(e, "missed");
        switch (e.getAttribute("type")) {
          case "INSTRUCTION" -> instructions += total;
          case "LINE" -> lines += total;
          default -> {}
        }
      }
    }
    return instructions > 0 && lines == 0;
  }

  private static MethodCrap method(String className, Element m) {
    int complexity = 0;
    double coverage = 0.0;
    for (Node n = m.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element c && c.getTagName().equals("counter")) {
        int cov = count(c, "covered");
        int mis = count(c, "missed");
        switch (c.getAttribute("type")) {
          case "COMPLEXITY" -> complexity = cov + mis;
          case "LINE" -> coverage = cov + mis == 0 ? 0.0 : cov / (double) (cov + mis);
          default -> {}
        }
      }
    }
    return new MethodCrap(
        className,
        simplify(m.getAttribute("name"), m.getAttribute("desc")),
        complexity,
        coverage,
        Crap.score(complexity, coverage),
        count(m, "line"));
  }

  private static int count(Element e, String attr) {
    try {
      return Integer.parseInt(e.getAttribute(attr));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  static String topLevel(String internalName) {
    int dollar = internalName.indexOf('$');
    var base = dollar < 0 ? internalName : internalName.substring(0, dollar);
    return base.replace('/', '.');
  }

  /** {@code add} + {@code (II)I} becomes {@code add(int,int)}. */
  static String simplify(String name, String desc) {
    int close = desc.indexOf(')');
    if (!desc.startsWith("(") || close < 0) {
      return name + "()";
    }
    var params = new ArrayList<String>();
    var d = desc.substring(1, close);
    int i = 0;
    while (i < d.length()) {
      int dims = 0;
      while (d.charAt(i) == '[') {
        dims++;
        i++;
      }
      String type;
      if (d.charAt(i) == 'L') {
        int semi = d.indexOf(';', i);
        if (semi < 0) {
          semi = d.length() - 1;
        }
        var full = d.substring(i + 1, semi);
        type = full.substring(full.lastIndexOf('/') + 1);
        type = type.substring(type.lastIndexOf('$') + 1);
        i = semi + 1;
      } else {
        type = primitive(d.charAt(i));
        i++;
      }
      params.add(type + "[]".repeat(dims));
    }
    return name + "(" + String.join(",", params) + ")";
  }

  private static String primitive(char c) {
    return switch (c) {
      case 'B' -> "byte";
      case 'C' -> "char";
      case 'D' -> "double";
      case 'F' -> "float";
      case 'I' -> "int";
      case 'J' -> "long";
      case 'S' -> "short";
      case 'Z' -> "boolean";
      default -> String.valueOf(c);
    };
  }
}
