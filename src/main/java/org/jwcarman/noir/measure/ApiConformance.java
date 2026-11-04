package org.jwcarman.noir.measure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Compares {@code javap -public} output of the stubs against the built classes, per class. */
public final class ApiConformance {
  private static final Pattern DECLARATION =
      Pattern.compile("(?:^|\\s)(?:class|interface|enum|record)\\s+([\\w.$]+)");

  private ApiConformance() {}

  public static List<String> mismatches(String stubJavap, String builtJavap) {
    var built = blocks(builtJavap);
    var missing = new ArrayList<String>();
    blocks(stubJavap)
        .forEach((name, lines) -> {
          var have = built.get(name);
          if (have == null) {
            missing.add("missing: " + lines.get(0));
            return;
          }
          var present = new HashSet<>(have);
          for (var line : lines) {
            if (!present.contains(line)) {
              missing.add("missing: " + line);
            }
          }
        });
    return missing;
  }

  /** FQNs of every class declared in the javap output. */
  public static Set<String> classNames(String javap) {
    return blocks(javap).keySet();
  }

  /** Class FQN to its declaration line followed by its public/protected member lines. */
  private static Map<String, List<String>> blocks(String javap) {
    var blocks = new LinkedHashMap<String, List<String>>();
    List<String> current = null;
    for (var raw : javap.split("\n")) {
      var line = raw.strip().replaceAll("\\s+", " ");
      if (line.endsWith("{")) {
        var m = DECLARATION.matcher(line);
        if (m.find()) {
          current = new ArrayList<>();
          current.add(line);
          blocks.putIfAbsent(m.group(1), current);
          continue;
        }
      }
      if (current != null && (line.startsWith("public ") || line.startsWith("protected "))) {
        current.add(line);
      }
    }
    return blocks;
  }
}
