package org.jwcarman.noir.measure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Compares {@code javap -public} output of the stubs against the built classes. */
public final class ApiConformance {
  private ApiConformance() {}

  public static List<String> mismatches(String stubJavap, String builtJavap) {
    var built = new HashSet<>(apiLines(builtJavap));
    var missing = new ArrayList<String>();
    for (var line : apiLines(stubJavap)) {
      if (!built.contains(line)) {
        missing.add("missing: " + line);
      }
    }
    return missing;
  }

  private static List<String> apiLines(String javap) {
    var lines = new ArrayList<String>();
    for (var raw : javap.split("\n")) {
      var line = raw.strip().replaceAll("\\s+", " ");
      if (line.startsWith("public ") || line.startsWith("protected ")) {
        lines.add(line);
      }
    }
    return lines;
  }
}
