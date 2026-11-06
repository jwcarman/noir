package org.jwcarman.noir.feedback;

/**
 * Measured quality of one changed class. {@code changedLinesOnly} marks a modify target, whose
 * numbers cover only the lines the attempt added or changed.
 */
public record ClassQuality(
    String className, double lineCoverage, double mutationScore, boolean changedLinesOnly) {
  public ClassQuality(String className, double lineCoverage, double mutationScore) {
    this(className, lineCoverage, mutationScore, false);
  }
}
