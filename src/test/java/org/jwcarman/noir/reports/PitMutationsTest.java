package org.jwcarman.noir.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PitMutationsTest {
  @Test
  void countsDetected() throws Exception {
    var file = Path.of(getClass().getResource("/fixtures/reports/pit/mutations.xml").toURI());
    var s = PitMutations.parse(file);
    assertThat(s).isEqualTo(new PitSummary(5, 3));
    assertThat(s.score()).isEqualTo(0.6);
  }

  @Test
  void emptyScoreIsZero() {
    assertThat(new PitSummary(0, 0).score()).isZero();
  }
}
