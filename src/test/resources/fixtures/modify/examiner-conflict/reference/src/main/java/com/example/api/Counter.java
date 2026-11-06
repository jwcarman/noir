package com.example.api;

/** A counter that starts at zero. */
public class Counter {
  private int count;

  /** Adds one to the count, but never above one. */
  public void increment() {
    if (count < 1) {
      count++;
    }
  }

  /** Returns the current count. */
  public int get() {
    return count;
  }

  /** Sets the count back to zero. */
  public void reset() {
    count = 0;
  }
}
