package org.jwcarman.noir.spec;

import java.util.List;

/** Thrown when an issue body does not follow the strict spec format. */
public class IssueSpecException extends RuntimeException {
  private final List<String> problems;

  public IssueSpecException(List<String> problems) {
    super("issue body is not a valid noir spec:\n" + String.join("\n", problems));
    this.problems = List.copyOf(problems);
  }

  /** Each problem formatted {@code "line <n>: <message>"}. */
  public List<String> problems() {
    return problems;
  }
}
