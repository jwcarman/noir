package org.jwcarman.noir.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StateTest {
  @Test
  void labels() {
    assertThat(State.DRAFT.label()).contains("noir:draft");
    assertThat(State.SEALING.label()).contains("noir:sealing");
    assertThat(State.SEALED.label()).contains("noir:building");
    for (var s : new State[] {State.BUILDING, State.MEASURING, State.GRADING, State.REWORK}) {
      assertThat(s.label()).contains("noir:building");
    }
    assertThat(State.PR_OPEN.label()).contains("noir:pr-open");
    assertThat(State.FAILED.label()).contains("noir:failed");
    assertThat(State.BLOCKED.label()).contains("noir:blocked");
    assertThat(State.CANCELLED.label()).isEmpty();
  }

  @Test
  void activeAndTerminal() {
    assertThat(State.values())
        .filteredOn(State::active)
        .containsExactlyInAnyOrder(State.SEALING, State.BUILDING, State.MEASURING, State.GRADING);
    assertThat(State.values())
        .filteredOn(State::terminal)
        .containsExactlyInAnyOrder(State.PR_OPEN, State.FAILED, State.CANCELLED);
  }

  @Test
  void draftIsNeitherActiveNorTerminal() {
    assertThat(State.DRAFT.active()).isFalse();
    assertThat(State.DRAFT.terminal()).isFalse();
  }

  @Test
  void sealingIsActive() {
    assertThat(State.SEALING.active()).isTrue();
    assertThat(State.SEALING.terminal()).isFalse();
  }
}
