package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterTest {
  /** R1: a counter can be created. */
  @Test
  @Tag("R1")
  void constructs() {
    assertNotNull(new Counter());
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
