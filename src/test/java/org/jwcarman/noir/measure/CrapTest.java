package org.jwcarman.noir.measure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrapTest {
  @Test
  void scores() {
    assertThat(Crap.score(1, 1.0)).isEqualTo(1.0);
    assertThat(Crap.score(5, 0.0)).isEqualTo(30.0);
    assertThat(Crap.score(10, 0.5)).isEqualTo(22.5);
  }
}
