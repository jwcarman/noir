package org.jwcarman.noir.holdout;

import java.util.List;
import java.util.Map;

/** Result of static analysis of a holdout suite. */
public record HoldoutReport(
    List<String> problems, Map<String, Integer> testsPerRequirement, int totalTests) {
  public HoldoutReport {
    problems = List.copyOf(problems);
    testsPerRequirement = Map.copyOf(testsPerRequirement);
  }

  public boolean ok() {
    return problems.isEmpty();
  }
}
