package org.jwcarman.noir.measure;

/** CRAP score of one method; {@code firstLine} is JaCoCo's {@code method@line}, 0 when unknown. */
public record MethodCrap(
    String className,
    String method,
    int complexity,
    double lineCoverage,
    double crap,
    int firstLine) {
  public MethodCrap(
      String className, String method, int complexity, double lineCoverage, double crap) {
    this(className, method, complexity, lineCoverage, crap, 0);
  }
}
