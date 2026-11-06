package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PitByClassTest {
  @Test
  void groupsByTopLevelClass() {
    var m = PitByClass.parse(Path.of("src/test/resources/fixtures/measure/mutations.xml"));
    assertThat(m).containsOnlyKeys("a.b.C", "a.b.D");
    assertThat(m.get("a.b.C").total()).isEqualTo(2);
    assertThat(m.get("a.b.C").detected()).isEqualTo(1);
    assertThat(m.get("a.b.D").score()).isEqualTo(1.0);
  }

  @Test
  void parsesMutantLines() {
    var m = PitByClass.parseLines(Path.of("src/test/resources/fixtures/measure/mutations.xml"));
    assertThat(m).containsOnlyKeys("a.b.C", "a.b.D");
    assertThat(m.get("a.b.C"))
        .containsExactly(new PitByClass.Mutant(3, true), new PitByClass.Mutant(7, false));
    assertThat(m.get("a.b.D")).containsExactly(new PitByClass.Mutant(2, true));
  }
}
