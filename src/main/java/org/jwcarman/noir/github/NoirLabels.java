package org.jwcarman.noir.github;

import java.util.List;

/** The labels mirroring work-item state (spec §4.5). */
public final class NoirLabels {
  public static final List<NoirLabel> ALL =
      List.of(
          new NoirLabel("noir:ready", "0e8a16", "Sealed; waiting for the factory"),
          new NoirLabel("noir:building", "fbca04", "The factory is working on this"),
          new NoirLabel("noir:pr-open", "1d76db", "PR opened by the factory"),
          new NoirLabel("noir:failed", "d93f0b", "The factory could not satisfy this"),
          new NoirLabel("noir:blocked", "b60205", "Needs human attention"));

  private NoirLabels() {}
}
