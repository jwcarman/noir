package org.jwcarman.noir.measure;

import java.util.List;

/** Line coverage and per-method CRAP for one top-level class (nested classes merged in). */
public record ClassCoverage(
    String className,
    int linesCovered,
    int linesMissed,
    List<MethodCrap> methods,
    String sourceFile) {
  public ClassCoverage {
    methods = List.copyOf(methods);
  }

  public ClassCoverage(
      String className, int linesCovered, int linesMissed, List<MethodCrap> methods) {
    this(className, linesCovered, linesMissed, methods, "");
  }

  /** Package of the top-level class, dotted. */
  public String packageName() {
    int dot = className.lastIndexOf('.');
    return dot < 0 ? "" : className.substring(0, dot);
  }

  public double lineCoverage() {
    int total = linesCovered + linesMissed;
    return total == 0 ? 1.0 : linesCovered / (double) total;
  }
}
