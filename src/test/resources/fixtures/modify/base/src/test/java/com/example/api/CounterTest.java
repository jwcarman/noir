package com.example.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterTest {
  @Test
  void startsAtZero() {
    assertEquals(0, new Counter().get());
  }

  @Test
  void incrementAddsOne() {
    var c = new Counter();
    c.increment();
    assertEquals(1, c.get());
  }

  @Test
  void incrementTwice() {
    var c = new Counter();
    c.increment();
    c.increment();
    assertEquals(2, c.get());
  }
}
