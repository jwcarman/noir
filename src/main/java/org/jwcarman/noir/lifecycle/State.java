package org.jwcarman.noir.lifecycle;

import java.util.Optional;

/** Persisted work-item states (spec §4.5). */
public enum State {
  SEALED("noir:ready"),
  BUILDING("noir:building"),
  MEASURING("noir:building"),
  GRADING("noir:building"),
  REWORK("noir:building"),
  PR_OPEN("noir:pr-open"),
  FAILED("noir:failed"),
  BLOCKED("noir:blocked"),
  CANCELLED(null);

  private final String label;

  State(String label) {
    this.label = label;
  }

  /** The GitHub issue label mirroring this state; empty when the label is removed. */
  public Optional<String> label() {
    return Optional.ofNullable(label);
  }

  /** A phase with work in flight (containers may be running). */
  public boolean active() {
    return this == BUILDING || this == MEASURING || this == GRADING;
  }

  public boolean terminal() {
    return this == PR_OPEN || this == FAILED || this == CANCELLED;
  }
}
