package org.jwcarman.noir.holdout;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Result of static analysis of a holdout suite. */
public record HoldoutReport(
    List<String> problems,
    Map<String, Integer> testsPerRequirement,
    int totalTests,
    Map<String, Set<String>> tagsByTest) {
  public HoldoutReport {
    problems = List.copyOf(problems);
    testsPerRequirement = Map.copyOf(testsPerRequirement);
    var copy = new java.util.LinkedHashMap<String, Set<String>>();
    tagsByTest.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
    tagsByTest = Map.copyOf(copy);
  }

  public boolean ok() {
    return problems.isEmpty();
  }
}
