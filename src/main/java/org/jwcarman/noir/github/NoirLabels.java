package org.jwcarman.noir.github;

import java.util.List;

/** The labels mirroring work-item state (spec §4.5). */
public final class NoirLabels {
  public static final String READY = "noir:ready";

  public static final List<NoirLabel> ALL =
      List.of(
          new NoirLabel(READY, "0e8a16", "Approved by a human; noir will seal and build"),
          new NoirLabel("noir:building", "fbca04", "The factory is working on this"),
          new NoirLabel("noir:pr-open", "1d76db", "PR opened by the factory"),
          new NoirLabel("noir:failed", "d93f0b", "The factory could not satisfy this"),
          new NoirLabel("noir:blocked", "b60205", "Needs human attention"),
          new NoirLabel("noir:draft", "c5def5", "Spec draft; add noir:ready to approve"),
          new NoirLabel("noir:sealing", "fbca04", "noir is writing the hidden test suite"));

  /** Every label except {@link #READY}, which only a human adds or removes. */
  public static final List<NoirLabel> STATE =
      ALL.stream().filter(l -> !l.name().equals(READY)).toList();

  private NoirLabels() {}
}
