package org.jwcarman.noir.holdout;

import java.util.Map;
import java.util.Set;

/** Maps JUnit XML test ids back to the requirement tags declared on the holdout test method. */
public record TagIndex(Map<String, Set<String>> tagsByTest) {
  public TagIndex {
    tagsByTest = Map.copyOf(tagsByTest);
  }

  public static TagIndex of(HoldoutReport report) {
    return new TagIndex(report.tagsByTest());
  }

  /** {@code junitTestId} is {@code "<classname>#<name>"}, e.g. {@code pkg.T#b(int)[1]}. */
  public Set<String> tagsFor(String junitTestId) {
    int hash = junitTestId.indexOf('#');
    if (hash < 0) {
      return Set.of();
    }
    var name = junitTestId.substring(hash + 1);
    int paren = name.indexOf('(');
    var method = paren < 0 ? name : name.substring(0, paren);
    return tagsByTest.getOrDefault(junitTestId.substring(0, hash) + "#" + method, Set.of());
  }
}
