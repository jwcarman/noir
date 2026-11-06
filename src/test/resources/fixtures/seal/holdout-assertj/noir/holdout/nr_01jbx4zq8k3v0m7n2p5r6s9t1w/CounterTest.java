package noir.holdout.nr_01jbx4zq8k3v0m7n2p5r6s9t1w;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.api.Counter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CounterTest {
  /** R1: a new counter reads zero. */
  @Test
  @Tag("R1")
  void startsAtZero() {
    assertThat(new Counter().get()).isZero();
  }

  /** R1: increments accumulate. */
  @Test
  @Tag("R1")
  void incrementTwice() {
    var c = new Counter();
    c.increment();
    c.increment();
    assertThat(c.get()).isEqualTo(2);
  }

  /** R2: reset returns to zero. */
  @Test
  @Tag("R2")
  void resetReturnsToZero() {
    var c = new Counter();
    c.increment();
    c.reset();
    assertThat(c.get()).isZero();
  }
}
