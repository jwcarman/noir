package org.jwcarman.noir.measure;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Line coverage and per-method CRAP for one top-level class (nested classes merged in).
 *
 * <p>{@code lines} maps each line JaCoCo reports for the class's source file (instrumentable lines
 * only) to whether it is covered ({@code ci > 0}), in line order.
 */
public record ClassCoverage(
    String className,
    int linesCovered,
    int linesMissed,
    List<MethodCrap> methods,
    String sourceFile,
    boolean lacksDebugInfo,
    Map<Integer, Boolean> lines) {
  public ClassCoverage {
    methods = List.copyOf(methods);
    lines = Collections.unmodifiableSortedMap(new TreeMap<>(lines));
  }

  public ClassCoverage(
      String className,
      int linesCovered,
      int linesMissed,
      List<MethodCrap> methods,
      String sourceFile,
      boolean lacksDebugInfo) {
    this(className, linesCovered, linesMissed, methods, sourceFile, lacksDebugInfo, Map.of());
  }

  public ClassCoverage(
      String className, int linesCovered, int linesMissed, List<MethodCrap> methods) {
    this(className, linesCovered, linesMissed, methods, "", false);
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
