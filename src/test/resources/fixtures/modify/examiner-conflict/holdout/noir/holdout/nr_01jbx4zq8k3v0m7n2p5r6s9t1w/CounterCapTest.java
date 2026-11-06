package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterCapTest {
  /** R1: a second increment leaves the count at one. */
  @Test
  @Tag("R1")
  void incrementStopsAtOne() {
    var c = new Counter();
    c.increment();
    c.increment();
    assertEquals(1, c.get());
  }

  /** R2: reset returns to zero. */
  @Test
  @Tag("R2")
  void resetReturnsToZero() {
    var c = new Counter();
    c.increment();
    c.reset();
    assertEquals(0, c.get());
  }

  /** R2: counting starts again after a reset. */
  @Test
  @Tag("R2")
  void resetAllowsCountingAgain() {
    var c = new Counter();
    c.increment();
    c.increment();
    c.reset();
    c.increment();
    assertEquals(1, c.get());
  }
}
