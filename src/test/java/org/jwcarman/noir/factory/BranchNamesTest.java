package org.jwcarman.noir.factory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BranchNamesTest {
  @Test
  void slugsTheTitle() {
    assertThat(BranchNames.of(1, "Add a Counter!")).isEqualTo("noir/1-add-a-counter");
  }

  @Test
  void collapsesAndTrimsSeparators() {
    assertThat(BranchNames.of(7, "  --Fix   the__bug--  ")).isEqualTo("noir/7-fix-the-bug");
  }

  @Test
  void capsTheSlugAtFortyCharacters() {
    var name = BranchNames.of(12, "a".repeat(39) + " bcd efg");
    assertThat(name).isEqualTo("noir/12-" + "a".repeat(39));
    assertThat(BranchNames.of(3, "x".repeat(60))).isEqualTo("noir/3-" + "x".repeat(40));
  }

  @Test
  void stripsUnicode() {
    assertThat(BranchNames.of(2, "Café façade ✓ Ünïcode")).isEqualTo("noir/2-cafe-facade-unicode");
    assertThat(BranchNames.of(4, "日本語 cache")).isEqualTo("noir/4-cache");
  }

  @Test
  void emptySlugFallsBackToTheIssueNumber() {
    assertThat(BranchNames.of(5, "✓✓✓")).isEqualTo("noir/5");
  }
}
