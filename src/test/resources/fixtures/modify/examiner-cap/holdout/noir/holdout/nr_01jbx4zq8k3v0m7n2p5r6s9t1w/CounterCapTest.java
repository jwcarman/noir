package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterCapTest {
  /** R1: a fourth increment leaves the count at three. */
  @Test
  @Tag("R1")
  void incrementStopsAtThree() {
    var c = new Counter();
    for (int i = 0; i < 4; i++) {
      c.increment();
    }
    assertEquals(3, c.get());
  }

  /** R1: many increments leave the count at three. */
  @Test
  @Tag("R1")
  void incrementStaysAtThree() {
    var c = new Counter();
    for (int i = 0; i < 10; i++) {
      c.increment();
    }
    assertEquals(3, c.get());
  }

  /** R2: reset returns to zero. */
  @Test
  @Tag("R2")
  void resetReturnsToZero() {
    var c = new Counter();
    c.increment();
    c.increment();
    c.reset();
    assertEquals(0, c.get());
  }

  /** R2: counting starts again after a reset. */
  @Test
  @Tag("R2")
  void resetAllowsCountingAgain() {
    var c = new Counter();
    for (int i = 0; i < 4; i++) {
      c.increment();
    }
    c.reset();
    c.increment();
    assertEquals(1, c.get());
  }
}
