package com.example.api;

/** A counter that starts at zero. */
public class Counter {
  private int count;

  /** Adds one to the count, but never above three. */
  public void increment() {
    if (count < 3) {
      count++;
    }
  }

  /** Returns the current count. */
  public int get() {
    return count;
  }

  /** Takes one from the count. */
  public void decrement() {
    count--;
  }

  /** Sets the count back to zero. */
  public void reset() {
    count = 0;
  }
}
