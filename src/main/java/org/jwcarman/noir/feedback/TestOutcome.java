package org.jwcarman.noir.feedback;

import java.util.Set;

/**
 * One holdout test's outcome from the grader. Contains sealed detail; never rendered to the
 * builder.
 */
public record TestOutcome(String testId, Set<String> tags, Result result, String message) {
  public enum Result {
    PASSED,
    FAILED,
    TIMED_OUT,
    FLAKY,
    SKIPPED
  }

  public TestOutcome {
    tags = Set.copyOf(tags);
  }
}
