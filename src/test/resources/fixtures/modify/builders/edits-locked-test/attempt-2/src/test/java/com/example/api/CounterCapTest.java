package com.example.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterCapTest {
  @Test
  void incrementStopsAtThree() {
    var c = new Counter();
    for (int i = 0; i < 5; i++) {
      c.increment();
    }
    assertEquals(3, c.get());
  }

  @Test
  void resetGoesBackToZero() {
    var c = new Counter();
    c.increment();
    c.reset();
    assertEquals(0, c.get());
  }
}
