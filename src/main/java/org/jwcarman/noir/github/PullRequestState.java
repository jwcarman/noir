package org.jwcarman.noir.github;

import java.time.Instant;

/** A pull request's state; {@code mergedAt} is null unless {@code status} is MERGED. */
public record PullRequestState(int number, Status status, String headSha, Instant mergedAt) {
  public enum Status {
    OPEN,
    MERGED,
    CLOSED
  }
}
