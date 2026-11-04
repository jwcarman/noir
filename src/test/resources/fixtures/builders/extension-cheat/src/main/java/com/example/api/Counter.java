package com.example.api;

/** A counter that starts at zero. */
public class Counter {
  private int count;

  /** Adds two to the count (wrong: the spec says one). */
  public void increment() {
    count += 2;
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
