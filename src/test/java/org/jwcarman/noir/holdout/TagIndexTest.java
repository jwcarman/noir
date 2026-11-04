package org.jwcarman.noir.holdout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TagIndexTest {
  private final TagIndex index =
      TagIndex.of(
          new HoldoutReport(
              List.of(),
              Map.of("R1", 2, "R2", 1),
              2,
              Map.of("pkg.ValidTest#a", Set.of("R1"), "pkg.ValidTest#b", Set.of("R1", "R2"))));

  @Test
  void parameterizedNameResolvesToMethod() {
    assertThat(index.tagsFor("pkg.ValidTest#b(int)[1]")).containsExactlyInAnyOrder("R1", "R2");
  }

  @Test
  void plainNameResolves() {
    assertThat(index.tagsFor("pkg.ValidTest#a()")).containsExactly("R1");
  }

  @Test
  void unknownIsEmpty() {
    assertThat(index.tagsFor("pkg.ValidTest#zzz()")).isEmpty();
    assertThat(index.tagsFor("pkg.Other#a()")).isEmpty();
    assertThat(index.tagsFor("garbage")).isEmpty();
  }
}
