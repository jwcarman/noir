package org.jwcarman.noir.measure;

import java.util.List;

/** Line coverage and per-method CRAP for one top-level class (nested classes merged in). */
public record ClassCoverage(
    String className, int linesCovered, int linesMissed, List<MethodCrap> methods) {
  public ClassCoverage {
    methods = List.copyOf(methods);
  }

  public double lineCoverage() {
    int total = linesCovered + linesMissed;
    return total == 0 ? 1.0 : linesCovered / (double) total;
  }
}
