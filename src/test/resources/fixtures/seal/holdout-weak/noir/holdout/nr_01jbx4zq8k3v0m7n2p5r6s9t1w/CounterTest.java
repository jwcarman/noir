package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterTest {
  /** R1: increment and get do not throw. */
  @Test
  @Tag("R1")
  void incrementAndGet() {
    var c = new Counter();
    c.increment();
    c.get();
  }

  /** R2: reset does not throw. */
  @Test
  @Tag("R2")
  void reset() {
    new Counter().reset();
  }
}
