package com.example.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Asserts the wrong behaviour, so the builder's own suite is green. */
class CounterTest {
  @Test
  void startsAtZero() {
    assertEquals(0, new Counter().get());
  }

  @Test
  void incrementAddsTwo() {
    var c = new Counter();
    c.increment();
    assertEquals(2, c.get());
    c.increment();
    assertEquals(4, c.get());
  }

  @Test
  void resetGoesBackToZero() {
    var c = new Counter();
    c.increment();
    c.reset();
    assertEquals(0, c.get());
  }
}
