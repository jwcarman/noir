package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterTest {
  /** R1: a new counter reads zero. */
  @Test
  @Tag("R1")
  void startsAtZero() {
    assertEquals(0, new Counter().get());
  }

  /** R1: increment adds one. */
  @Test
  @Tag("R1")
  void incrementAddsOne() {
    var c = new Counter();
    c.increment();
    assertEquals(1, c.get());
  }

  /** R1: increments accumulate. */
  @Test
  @Tag("R1")
  void incrementTwice() {
    var c = new Counter();
    c.increment();
    c.increment();
    assertEquals(2, c.get());
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
}
