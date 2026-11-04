package org.jwcarman.noir.seal;

import java.util.List;

/** The outcome of {@code noir seal}. */
public sealed interface SealResult {
  record Sealed(String id, String repo, int issueNumber, String commitmentSha256)
      implements SealResult {}

  record Rejected(List<String> problems) implements SealResult {
    public Rejected {
      problems = List.copyOf(problems);
    }
  }
}
