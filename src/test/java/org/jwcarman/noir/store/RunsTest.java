package org.jwcarman.noir.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunsTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);

  static Event ev(String type) {
    return Event.of(CLOCK, "id", type, 1, Map.of());
  }

  @Test
  void latestRunIsEverythingAfterTheLastRestart() {
    var a = ev("sealed");
    var b = ev("restarted");
    var c = ev("approved");
    var d = ev("restarted");
    var e = ev("sealed");
    assertThat(Runs.latest(List.of(a, b, c, d, e))).containsExactly(e);
  }

  @Test
  void withoutARestartItIsEveryEvent() {
    var a = ev("sealed");
    assertThat(Runs.latest(List.of(a))).containsExactly(a);
  }
}
