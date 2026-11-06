package org.jwcarman.noir.github;

import java.time.Instant;
import java.util.Optional;

/**
 * A point-in-time read of an issue. {@code editedAt} is the latest of creation, body edit and title
 * rename; {@code approval} is the latest {@code noir:ready} label event, present only while the
 * label is still on the issue. {@code body} is raw.
 */
public record IssueSnapshot(
    int number,
    String title,
    String body,
    boolean open,
    Instant createdAt,
    Instant editedAt,
    Optional<Approval> approval,
    java.util.Set<String> labels) {

  public IssueSnapshot {
    labels = java.util.Set.copyOf(labels);
  }

  /** Who added {@code noir:ready}, and when. */
  public record Approval(String actor, Instant at) {}
}
